/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one pure function from configuration to serving state (AD-4):
 * {@code (Spring-bound profiles, inventory file) -> validated InventorySnapshot},
 * invoked identically at boot and on every reload.
 * Bypasses the Spring property binder: the trees are direct-parsed with SnakeYAML,
 * which is what makes a 10,000-entry inventory load in milliseconds instead of
 * minutes.
 *
 * <p>The new trees are strict from birth, at every level: unknown keys anywhere in
 * the tree, non-string keys, unparseable or inet_aton-style range spellings, and
 * unresolvable references are startup errors naming the offending entry. The
 * legacy-shape leniency of {@code riptide.nodes} is deliberately not inherited
 * here. Secrets never appear in the inventory file; it carries references to named
 * credential sets only.</p>
 */
@Slf4j
public final class InventoryLoader {

    private static final Set<String> ROOT_KEYS = Set.of("riptide");
    private static final Set<String> RIPTIDE_KEYS = Set.of("snmp", "exporters");
    private static final Set<String> SNMP_KEYS = Set.of("agents");
    private static final Set<String> AGENT_KEYS = Set.of("credentials", "polling", "enabled", "port");
    private static final Set<String> EXPORTER_KEYS = Set.of("address", "observation-domain", "interfaces");

    private static final Set<String> PIN_KEYS = Set.of("name", "alias", "high-speed");

    private static final long MAX_OBSERVATION_DOMAIN = 0xFFFF_FFFFL;

    private static final int SNMP_DEFAULT_PORT = 161;

    private static final int MAX_PORT = 65535;

    /** ifHighSpeed is a Gauge32 in Mbit/s. */
    private static final long MAX_HIGH_SPEED = 0xFFFF_FFFFL;

    private static final java.util.regex.Pattern CANONICAL_IF_INDEX = java.util.regex.Pattern.compile("[1-9][0-9]*");

    // bounded diagnostics: name a readable number of half-finished entries, then count
    private static final int MAX_NAMED_EMPTY_ENTRIES = 20;

    /**
     * How many bad entries the problem report names before it counts the rest (#630).
     *
     * <p>Deliberately the same number as {@link #MAX_NAMED_EMPTY_ENTRIES}, and named
     * separately because it bounds something else: that constant bounds entries that
     * declare nothing, this one bounds entries that are wrong. An operator should meet
     * one style of bounded diagnostic, which is also why {@code
     * ObsoleteKeys.MAX_NAMED_KEYS} mirrors the number.</p>
     */
    private static final int MAX_NAMED_BAD_ENTRIES = MAX_NAMED_EMPTY_ENTRIES;

    /**
     * How many problems one entry may print before its own remainder is counted.
     *
     * <p>Four times smaller than the entry bound, because an entry's problems are
     * usually one mistake repeated — a generator emitting blank aliases across a hundred
     * interface pins — while entries are what actually differ. Five examples and a count
     * identify the class; five hundred lines bury every other entry in the report.</p>
     */
    private static final int MAX_NAMED_PROBLEMS_PER_ENTRY = 5;

    // roughly 700k entries; generous but finite, so a runaway generated file is a
    // named error instead of an OOM
    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    /** Checked before the read, because the code-point limit only bounds the parser. */
    private static final long MAX_FILE_BYTES = CODE_POINT_LIMIT;

    private InventoryLoader() {
    }

    /**
     * Loads and validates the inventory. A {@code null} file means an empty
     * inventory, which is valid; a set but unreadable file is an error naming the
     * problem.
     */
    public static ParseResult load(final SnmpProfilesConfig profiles, final Path file) {
        if (file == null) {
            return new ParseResult(InventorySnapshot.empty(), List.of());
        }
        requireReadableSize(file);
        final String content;
        try {
            content = Files.readString(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        return parseWithWarnings(profiles, content, file.toString());
    }

    /**
     * The code-point limit bounds the parser, not the read: bytes, chars, a copy and
     * the object graph are all live before it applies, so a runaway file OOMs first,
     * and an Error out of a reload cycle kills the schedule for the process lifetime.
     * Fail on size with the same file-naming message instead.
     */
    private static void requireReadableSize(final Path file) {
        try {
            final long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new IllegalStateException(
                        "Inventory file %s is %d bytes, over the %d byte limit: split it or generate less."
                                .formatted(file, size, MAX_FILE_BYTES));
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
    }

    /**
     * A parsed candidate plus the operator-facing warnings its walk produced,
     * pre-formatted (no SLF4J placeholders — the {@code agents: {}} lesson).
     */
    public record ParseResult(InventorySnapshot snapshot, List<String> warnings) {
        public ParseResult {
            warnings = List.copyOf(warnings);
        }

        /** Flushes through the loader's own logger, so grep-by-logger stays stable. */
        public void flushWarnings() {
            this.warnings.forEach(log::warn);
        }
    }

    /**
     * Parse-and-warn-immediately convenience where parsing IS the publication: tests,
     * benches and the converter round-trip harness. Production publication paths (boot,
     * both reloaders) go through {@link #parseWithWarnings} or {@link #load} and flush
     * only on publication — a rejected candidate whose warnings already hit the log
     * reads as though the warned-about state went live when nothing changed (#539).
     */
    public static InventorySnapshot parse(final SnmpProfilesConfig profiles, final String content,
                                          final String sourceName) {
        final ParseResult result = parseWithWarnings(profiles, content, sourceName);
        result.flushWarnings();
        return result.snapshot();
    }

    /**
     * The pure core: parses and validates inventory content into a snapshot, collecting
     * warnings instead of logging them. The walk's warnings read as descriptions of
     * live state ("it still matches, so it can shadow wider ranges"), so the caller
     * flushes them only once the candidate is actually published.
     *
     * <p>Two passes, staged rather than hoisted (#630). Pass 1 walks every entry,
     * validates it, and collects the problems alongside the entries that survived; if
     * anything was collected it fails once with the lot, so an operator fixing a
     * hand-written file meets every mistake in one boot instead of one per restart. The
     * report is bounded by entries, not problems ({@link Problems}). Pass 2 feeds the
     * matcher builders, fail-fast and unchanged, and runs only on a clean pass 1 — which
     * is what keeps {@link PinnedPrefixMatcher}'s duplicate-poisoning unreachable rather
     * than reversed: no builder is ever handed an entry that failed validation. The cost
     * is that a duplicate is never reported alongside a value problem, so five typos and
     * one duplicate take two boots.</p>
     */
    public static ParseResult parseWithWarnings(final SnmpProfilesConfig profiles, final String content,
                                                final String sourceName) {
        final List<String> warnings = new ArrayList<>();
        final Problems problems = new Problems();
        final Map<String, Object> root = parseYaml(content, sourceName, problems);
        final Map<String, Object> riptide;
        final Map<String, Object> snmp;
        final List<AgentCandidate> agentCandidates;
        final List<ExporterEntry> exporterCandidates;
        try {
            requireKnownKeys("the file root", root, ROOT_KEYS, problems);
            riptide = section(root, "riptide", problems);
            requireKnownKeys("'riptide'", riptide, RIPTIDE_KEYS, problems);
            snmp = section(riptide, "snmp", problems);
            requireKnownKeys("'riptide.snmp'", snmp, SNMP_KEYS, problems);
            // pass 1: both trees, so a file broken in agents AND exporters reports both
            agentCandidates = validateAgents(profiles, section(snmp, "agents", problems), warnings, problems);
            exporterCandidates = validateExporters(section(riptide, "exporters", problems), warnings, problems);
        } catch (final IllegalStateException structural) {
            // a tree level that is not a mapping cannot be walked, so it ends the pass —
            // but never alone. Whatever was collected before it must still reach the
            // operator, or a stray key at one level plus a bad section below it reports
            // the section and silently swallows the key, leaving them blinder than they
            // were before problems were collected at all
            problems.add(problemText(structural, "inventory file", sourceName), structural);
            throw problems.report(sourceName);
        }
        if (!problems.isEmpty()) {
            // the report names the file itself, so it is raised outside the wrap below
            throw problems.report(sourceName);
        }

        try {
            // declaredness travels with the build: an explicit empty mapping is a deliberate
            // decommission, an absent tree over a populated one is a torn read. The marker is
            // honoured at any ancestor, because the reasoning is the ancestor's too: a torn
            // write dies at a bare or missing key, and `snmp: {}` or `riptide: {}` can only
            // be authored — truncation never replaces a populated tree with a literal {}
            final boolean riptideEmpty = root.get("riptide") instanceof java.util.Map<?, ?> r && r.isEmpty();
            final boolean snmpEmpty = riptide.get("snmp") instanceof java.util.Map<?, ?> m && m.isEmpty();
            return new ParseResult(new InventorySnapshot(
                    agents(agentCandidates), exporters(exporterCandidates),
                    snmp.get("agents") instanceof java.util.Map || snmpEmpty || riptideEmpty,
                    riptide.get("exporters") instanceof java.util.Map || riptideEmpty), warnings);
        } catch (final IllegalStateException e) {
            // uniform operator experience: every entry-level error names the file,
            // including the matcher's duplicate-coverage errors
            throw new IllegalStateException(
                    "Inventory file %s: %s".formatted(sourceName, e.getMessage()), e);
        }
    }

    /**
     * What one validating pass collected, bounded by <em>entries</em>.
     *
     * <p>An entry may contribute several lines — an exporter's interface pins are their
     * own iteration, and so are the unknown keys at one tree level — but it consumes one
     * named slot. Bounding problems instead would let one pathological entry fill every
     * slot while four hundred other broken entries go unmentioned, which is precisely
     * the failure #630 exists to kill. Lines within one entry are bounded too, or a
     * generated exporter with five hundred blank aliases prints five hundred lines.</p>
     *
     * <p>The bound and the "listed no further" phrasing are this file's existing
     * bounded-diagnostic idiom; see {@link #MAX_NAMED_BAD_ENTRIES}.</p>
     */
    private static final class Problems {

        /**
         * One entry's problems, kept together so the entry is what the outer bound counts.
         *
         * <p>Most entries hold exactly one: the first check they failed. The value checks
         * are a dependency graph, not a list, and a check skipped because its input
         * failed is indistinguishable in a report from one that passed.</p>
         */
        static final class Entry {

            private final List<String> named = new ArrayList<>();
            private final List<Throwable> causes = new ArrayList<>();
            private int count;

            /** Keeps the text verbatim: it is what ~46 tests and every operator read. */
            void add(final String problem, final Throwable cause) {
                this.count++;
                if (this.named.size() < MAX_NAMED_PROBLEMS_PER_ENTRY) {
                    this.named.add(problem);
                    if (cause != null) {
                        this.causes.add(cause);
                    }
                }
            }

            boolean isEmpty() {
                return this.count == 0;
            }
        }

        private final List<Entry> named = new ArrayList<>();
        private int entryCount;

        /** Files one entry's problems, if it had any. One entry, one slot. */
        void record(final Entry entry) {
            if (entry.isEmpty()) {
                return;
            }
            this.entryCount++;
            if (this.named.size() < MAX_NAMED_BAD_ENTRIES) {
                this.named.add(entry);
            }
        }

        /** The ordinary case: one entry with one problem. */
        void add(final String problem, final Throwable cause) {
            final Entry entry = new Entry();
            entry.add(problem, cause);
            record(entry);
        }

        boolean isEmpty() {
            return this.entryCount == 0;
        }

        /**
         * One failure carrying the lot, naming the file once in the header rather than
         * once per line.
         *
         * <p>{@code '\n'} rather than the platform separator: this text is operator
         * facing, {@code ConfigFileReloader} string-compares it every poll to keep a
         * repeated failure quiet, and every other message in this file renders
         * identically on every platform.</p>
         */
        IllegalStateException report(final String sourceName) {
            final StringBuilder text = new StringBuilder("Inventory file %s carries problems in %s:"
                    .formatted(sourceName, entries(this.entryCount)));
            for (final Entry entry : this.named) {
                for (final String problem : entry.named) {
                    text.append('\n').append("  - ").append(problem);
                }
                if (entry.count > entry.named.size()) {
                    text.append('\n').append("    and %d more in this entry, listed no further"
                            .formatted(entry.count - entry.named.size()));
                }
            }
            if (this.entryCount > this.named.size()) {
                text.append('\n').append("  problems in %s are listed no further"
                        .formatted(entries(this.entryCount - this.named.size())));
            }
            final IllegalStateException report = new IllegalStateException(text.toString());
            // the throwables the lines came from: a collected report has no single cause,
            // and a stack of nothing but loader frames loses what an ifIndex key's
            // NumberFormatException said about why it was rejected
            for (final Entry entry : this.named) {
                entry.causes.forEach(report::addSuppressed);
            }
            return report;
        }

        /** "1 entry", "6 entries": a count an operator checks against their file. */
        private static String entries(final int count) {
            return count == 1 ? "1 entry" : count + " entries";
        }
    }

    /**
     * The line one rejected entry contributes.
     *
     * <p>{@code getMessage()} is nullable, and a report line reading {@code null} names
     * neither the entry nor the rule, so the fallback names both what was rejected and
     * what rejected it.</p>
     */
    private static String problemText(final IllegalStateException e, final String kind, final String name) {
        return e.getMessage() != null ? e.getMessage()
                : "The %s '%s' was rejected by %s, which said nothing more."
                        .formatted(kind, name, e.getClass().getName());
    }

    /**
     * One agent range that survived validation: the parsed key, which only pass 1 has,
     * plus the entry pass 2 hands to the matcher.
     */
    private record AgentCandidate(IPAddressString address, AgentEntry entry) {
    }

    /**
     * Pass 1 over the agents tree: validate every range, collect one problem per bad one.
     *
     * <p>The recovery boundary is the iteration boundary, deliberately. The six value
     * checks below are a dependency graph rather than a list — the cleartext-width rule
     * reads the credential set the resolve step produced — so a check skipped because
     * its input failed would be indistinguishable in the report from one that passed.
     * One entry yields at most one problem here.</p>
     */
    private static List<AgentCandidate> validateAgents(final SnmpProfilesConfig profiles,
                                                       final Map<String, Object> agents,
                                                       final List<String> warnings,
                                                       final Problems problems) {
        // one instance per build: every range that names no profile shares it (FR-7)
        final PollingProfile defaultProfile =
                profiles.polling().getOrDefault("default", PollingProfile.builtInDefault());
        final List<AgentCandidate> candidates = new ArrayList<>(agents.size());
        int declaredNothing = 0;
        for (final Map.Entry<String, Object> entry : agents.entrySet()) {
            final Problems.Entry collected = new Problems.Entry();
            try {
                final Map<String, Object> entryBody = body(entry, "agent range");
                requireEntryKeys(entry.getKey(), "agent range", entryBody, AGENT_KEYS);
                final boolean enabled = enabled(entry.getKey(), entryBody.get("enabled"));
                final int port = port(entry.getKey(), entryBody.get("port"));
                final CredentialSet credentials = resolve(entry.getKey(), "credential set",
                        entryBody.get("credentials"), profiles.credentials());
                final Object pollingReference = entryBody.get("polling");
                // an explicit "default" is the spelled-out form of the omitted key, so both
                // resolve identically: operator-defined default wins, built-in otherwise
                final PollingProfile polling = pollingReference == null || "default".equals(pollingReference)
                        ? defaultProfile
                        : resolve(entry.getKey(), "polling profile", pollingReference, profiles.polling());
                // warn only once the key is known to be a usable range: an unparseable
                // key is about to fail hard, and telling the operator it is live and
                // shadowing would be the opposite of true
                final IPAddressString address = strictAddress(entry.getKey(), "agent range", false);
                requireSingleAddressForCleartext(entry.getKey(), address, credentials,
                        entryBody.get("credentials"));
                if (declaresNothing(entryBody)) {
                    declaredNothing++;
                    if (declaredNothing <= MAX_NAMED_EMPTY_ENTRIES) {
                        warnings.add(("Agent range '%s' declares nothing: it still matches, so it can shadow wider "
                                + "ranges, and with no credential set it is never polled. Give it a credential set, "
                                + "or spell the exclusion as 'enabled: false' if that is what you meant")
                                .formatted(entry.getKey()));
                    }
                }
                candidates.add(new AgentCandidate(address,
                        new AgentEntry(entry.getKey(), credentials, polling, enabled, port)));
            } catch (final IllegalStateException e) {
                collected.add(problemText(e, "agent range", entry.getKey()), e);
            }
            problems.record(collected);
        }
        if (declaredNothing > MAX_NAMED_EMPTY_ENTRIES) {
            // a generated inventory can carry thousands of these; naming every one
            // would bury the rest of startup (the bounded-diagnostic idiom)
            warnings.add("%d further agent ranges declare nothing and are listed no further"
                    .formatted(declaredNothing - MAX_NAMED_EMPTY_ENTRIES));
        }
        return candidates;
    }

    /**
     * Pass 2 over the agents tree: fail-fast, and reached only when pass 1 collected
     * nothing, so the builder's duplicate poisoning can never be caught and continued.
     */
    private static PinnedPrefixMatcher<AgentEntry> agents(final List<AgentCandidate> candidates) {
        final PinnedPrefixMatcher.Builder<AgentEntry> builder = PinnedPrefixMatcher.builder();
        for (final AgentCandidate candidate : candidates) {
            // never pinned (#543/#615): every agent range lands in the wildcard pool
            builder.add(candidate.entry().range(), candidate.address(), null, candidate.entry());
        }
        return builder.build();
    }

    /**
     * AD-8, enforced here rather than documented: a range wider than one address whose
     * credential set speaks v1 or v2c fails the build, with no override. Declaring a
     * credentialed range flips polling from opt-in to opt-out inside it, because a flow
     * from any in-range address registers that address and polls it, so a wide v1/v2c
     * range would offer its cleartext community to whatever answers.
     *
     * <p>The rule belongs to the (range, credential set) pairing, which is why it cannot
     * live on {@link CredentialSet#validate}: that runs at bind time before any range
     * exists, and one set is shared by every range naming it. It deliberately ignores
     * {@code enabled}: a carve-out becomes a live range with a one-character edit, and a
     * disabled range already has its references resolved, so a security rule must not be
     * weaker than a naming rule.</p>
     */
    private static void requireSingleAddressForCleartext(final String range, final IPAddressString address,
                                                         final CredentialSet credentials,
                                                         final Object reference) {
        if (credentials == null) {
            return;
        }
        if (credentials.version() == null) {
            // bind-time validation guarantees a version, but the set is a mutable bean
            // shared across ranges: name the range instead of surfacing a bare NPE that
            // escapes the file-naming wrapper
            throw new IllegalStateException(
                    "Agent range '%s' uses credential set '%s', which has no version.".formatted(range, reference));
        }
        // a switch expression with no default: adding a version becomes a compile error
        // here rather than a silently insecure pass
        final boolean cleartext = switch (credentials.version()) {
            case V1, V2C -> true;
            case V3 -> false;
        };
        // strictAddress has already narrowed this to a host or a single prefix block,
        // so isMultiple() is exactly "covers more than one address", in either family
        if (cleartext && address.getAddress().isMultiple()) {
            throw new IllegalStateException(
                    ("Agent range '%s' uses credential set '%s', which speaks %s, but is wider than a single "
                            + "address: the cleartext community would be sent to any in-range address that "
                            + "emits a flow. Either enumerate the devices as single addresses, or migrate the "
                            + "segment to a v3 credential set.")
                            // the raw reference, never the CredentialSet: the set carries no
                            // name of its own, so the object would not identify what to fix
                            .formatted(range, reference, credentials.version().name().toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * An entry is a half-finished edit when it carries no keys at all, or carries
     * only keys whose values were never filled in ({@code credentials:} with
     * nothing after it). Both build the same serving state: a range that matches,
     * shadows, and can never be polled. An entry naming a real value is deliberate,
     * even when that value implies no polling.
     */
    private static boolean declaresNothing(final Map<String, Object> entryBody) {
        return entryBody.values().stream().allMatch(Objects::isNull);
    }

    /**
     * Pass 1 over the exporters tree. One problem per bad exporter, as for agent ranges
     * — except that the interfaces map is itself an iteration, so its pins recover one
     * by one inside {@link #interfacePins}: a script-generated exporter with thirty
     * blank aliases used to be thirty boots. Those lines all belong to this one entry
     * and consume one slot of the report's bound. An exporter whose own address or pin
     * fails never reaches its interfaces, because those checks are its dependency graph.
     */
    private static List<ExporterEntry> validateExporters(final Map<String, Object> exporters,
                                                         final List<String> warnings,
                                                         final Problems problems) {
        final List<ExporterEntry> candidates = new ArrayList<>(exporters.size());
        for (final Map.Entry<String, Object> entry : exporters.entrySet()) {
            final Problems.Entry collected = new Problems.Entry();
            try {
                final Map<String, Object> entryBody = body(entry, "exporter");
                requireEntryKeys(entry.getKey(), "exporter", entryBody, EXPORTER_KEYS);
                final Object address = entryBody.get("address");
                if (address == null) {
                    throw new IllegalStateException("Exporter '%s' has no address — every enrichment entry needs one."
                            .formatted(entry.getKey()));
                }
                // prefixes allowed, like agent ranges: an entry may label and pin a whole
                // subnet, which is how a site-scoped label survives the move off the legacy
                // tree. Most specific still wins, so a bare host beats a prefix covering it
                final IPAddressString parsedAddress = strictAddress(String.valueOf(address),
                        "exporter '%s' address".formatted(entry.getKey()), false);
                final Long pin = observationDomain(entry.getKey(), entryBody.get("observation-domain"));
                final Map<Integer, InterfacePin> pins =
                        interfacePins(entry.getKey(), entryBody.get("interfaces"), warnings, collected);
                if (collected.isEmpty()) {
                    // skipped here, not three call frames away: an entry whose pins failed
                    // is not a candidate, whatever the guard before pass 2 decides
                    candidates.add(new ExporterEntry(entry.getKey(), parsedAddress, pin, pins));
                }
            } catch (final IllegalStateException e) {
                collected.add(problemText(e, "exporter", entry.getKey()), e);
            }
            problems.record(collected);
        }
        return candidates;
    }

    /**
     * Pass 2 over the exporters tree: the name, address and pin travel on the entry, so
     * this reads back what pass 1 validated rather than re-deriving any of it.
     */
    private static PinnedPrefixMatcher<ExporterEntry> exporters(final List<ExporterEntry> candidates) {
        final PinnedPrefixMatcher.Builder<ExporterEntry> builder = PinnedPrefixMatcher.builder();
        for (final ExporterEntry entry : candidates) {
            // name() IS the map key: pass 1 builds every entry from it. The duplicate
            // errors that name both parties are what pin this, in
            // InventoryLoaderTest.twoExportersOnTheSameAddressAndPinFailNamingBoth
            builder.add(entry.name(), entry.address(), entry.observationDomain(), entry);
        }
        return builder.build();
    }

    private static Map<String, Object> parseYaml(final String content, final String sourceName,
                                                 final Problems problems) {
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setAllowDuplicateKeys(false);
        try {
            final Map<?, ?> root = new Yaml(options).load(content);
            return root != null ? stringKeyed(root, "the file root", problems) : Map.of();
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not valid YAML: %s".formatted(sourceName, e.getMessage()), e);
        }
    }

    /**
     * Validates every key is a string, defusing SnakeYAML's YAML 1.1 implicit typing:
     * an unquoted {@code on:}, {@code no:} or {@code 123:} arrives as a Boolean or
     * Integer key and must be a named error, not a ClassCastException.
     *
     * <p>Collects rather than throws: this is itself a loop over a level, so a file with
     * three unquoted keys used to be three boots. A rejected key never lands in the
     * result, and staging makes the half-built map safe — pass 2 runs only when nothing
     * was collected.</p>
     */
    private static Map<String, Object> stringKeyed(final Map<?, ?> raw, final String where,
                                                   final Problems problems) {
        final Map<String, Object> typed = new LinkedHashMap<>(raw.size() * 2);
        final Problems.Entry level = new Problems.Entry();
        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                // the file is named once, by the report's header
                level.add("Key '%s' under %s is not a string — quote it."
                        .formatted(entry.getKey(), where), null);
                continue;
            }
            typed.put(key, entry.getValue());
        }
        problems.record(level);
        return typed;
    }

    /**
     * Throws rather than collects, and is the one place in pass 1 that does: a level
     * that is not a mapping has no entries to walk. The caller records it alongside
     * everything already collected, so nothing is lost.
     */
    private static Map<String, Object> section(final Map<String, Object> parent, final String key,
                                               final Problems problems) {
        final Object value = parent.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "'%s' must be a mapping, found %s.".formatted(key, value.getClass().getSimpleName()));
        }
        return stringKeyed(map, "'" + key + "'", problems);
    }

    private static Map<String, Object> body(final Map.Entry<String, Object> entry, final String kind) {
        final Object value = entry.getValue();
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "The %s '%s' must be a mapping, found %s."
                            .formatted(kind, entry.getKey(), value.getClass().getSimpleName()));
        }
        final Map<String, Object> typed = new LinkedHashMap<>(map.size() * 2);
        for (final Map.Entry<?, ?> bodyEntry : map.entrySet()) {
            if (!(bodyEntry.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        "The %s '%s' has a non-string key '%s' — quote it."
                                .formatted(kind, entry.getKey(), bodyEntry.getKey()));
            }
            typed.put(key, bodyEntry.getValue());
        }
        return typed;
    }

    private static <T> T resolve(final String range, final String kind, final Object reference,
                                 final Map<String, T> definitions) {
        if (reference == null) {
            return null;
        }
        final T resolved = definitions.get(String.valueOf(reference));
        if (resolved == null) {
            throw new IllegalStateException(
                    "Agent range '%s' references %s '%s' which is not defined."
                            .formatted(range, kind, reference));
        }
        return resolved;
    }

    /**
     * Collects rather than throws: this runs once per tree level, and a stray key at the
     * file root used to hide the one under {@code riptide.snmp} until the operator had
     * fixed the first and restarted. One level is one entry in the report, however many
     * of its keys are unknown.
     */
    private static void requireKnownKeys(final String where, final Map<String, Object> map,
                                         final Set<String> known, final Problems problems) {
        final Problems.Entry level = new Problems.Entry();
        for (final String key : map.keySet()) {
            if (!known.contains(key)) {
                // sorted: Set.of iteration order is salt-randomized, so the listed
                // keys would otherwise shuffle between runs of the same binary.
                // The file is named once, by the report's header
                level.add("Unknown key '%s' under %s; known keys are %s."
                        .formatted(key, where, new TreeSet<>(known)), null);
            }
        }
        problems.record(level);
    }

    private static void requireEntryKeys(final String name, final String kind,
                                         final Map<String, Object> entryBody, final Set<String> known) {
        for (final String key : entryBody.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalStateException(
                        "The %s '%s' has an unknown key '%s'; known keys are %s."
                                .formatted(kind, name, key, new TreeSet<>(known)));
            }
        }
    }

    /**
     * The UDP port the agent answers on. Restored for 0.9 after the agent-ranges story
     * fixed it at 161: the legacy tree exposes a per-node port, the shipped example
     * uses one, and so do the SNMP tests, so a device on a non-standard port would
     * silently lose enrichment at the cutover (walks to 161, fail, back off).
     */
    private static int port(final String range, final Object value) {
        if (value == null) {
            return SNMP_DEFAULT_PORT;
        }
        if (!(value instanceof Integer port)) {
            throw new IllegalStateException(
                    "Agent range '%s' has a port '%s' that is not a whole number.".formatted(range, value));
        }
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalStateException(
                    "Agent range '%s' has a port %d outside 1..%d.".formatted(range, port, MAX_PORT));
        }
        return port;
    }

    /**
     * The carve-out flag. Absent or an explicit YAML null reads as enabled, matching
     * how omitted credential and polling references read (a present-but-empty value
     * is indistinguishable from an absent key at the SnakeYAML layer). Only a real
     * boolean disables: YAML 1.1 resolves {@code no}/{@code off} to booleans, but a
     * quoted {@code "false"} is a String, and treating that as truthy would carve
     * out nothing while looking deliberate in the file.
     */
    private static boolean enabled(final String range, final Object value) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException(
                    "Agent range '%s' has a non-boolean enabled value '%s': write it unquoted as true or false."
                            .formatted(range, value));
        }
        return flag;
    }

    /**
     * Static per-ifIndex pins for one exporter. ifIndex keys may be written unquoted:
     * SnakeYAML hands those over as Integers, and unlike everywhere else in this tree
     * a numeric key here is what an operator means rather than a typo (the legacy
     * {@code riptide.nodes} spelling is unquoted too). Quoted keys must be canonical
     * decimal, so the two spellings can never disagree about which interface they
     * mean, and declaring one ifIndex both ways is an error rather than a silent
     * last-one-wins.
     *
     * <p>One problem per pin, not per exporter: an exporter with a hundred generated
     * pins, thirty of them blank, cost thirty boots when the first one ended the parse.
     * The pins are collected in this exporter's document order, which is what makes the
     * report stable across runs, and they are all reported under this one exporter
     * entry.</p>
     *
     * <p>One YAML wart survives and cannot be seen from here: an unquoted {@code 010}
     * is resolved to 8 by YAML 1.1 octal rules before the loader is handed the key.
     * Quote it, or write it without the leading zero.</p>
     */
    private static Map<Integer, InterfacePin> interfacePins(final String exporter, final Object value,
                                                            final List<String> warnings,
                                                            final Problems.Entry collected) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> pins)) {
            // the exporter's own shape, not a pin's: thrown, so it is the one problem
            // that entry reports and no pin is walked against a non-mapping
            throw new IllegalStateException(
                    "Exporter '%s' interfaces must be a mapping of ifIndex to pins, found %s."
                            .formatted(exporter, value.getClass().getSimpleName()));
        }
        final Map<Integer, InterfacePin> parsed = new LinkedHashMap<>(pins.size() * 2);
        final Set<Integer> declared = new HashSet<>(pins.size() * 2);
        for (final Map.Entry<?, ?> pin : pins.entrySet()) {
            try {
                final int ifIndex = ifIndex(exporter, pin.getKey());
                if (!declared.add(ifIndex)) {
                    // quoted and unquoted spellings are distinct keys to SnakeYAML, so its
                    // own duplicate-key check cannot see this one. Decided from the KEY,
                    // before the value is parsed: a first spelling whose value was rejected
                    // must not leave its twin looking unique
                    throw new IllegalStateException(
                            ("Exporter '%s' pins interface %d twice, once quoted and once not: "
                                    + "keep one spelling.").formatted(exporter, ifIndex));
                }
                parsed.put(ifIndex, interfacePin(exporter, ifIndex, pin.getValue(), warnings));
            } catch (final IllegalStateException e) {
                // a rejected pin never lands in the result; the collected problem stops
                // this half-built map from becoming a candidate anyway
                collected.add(problemText(e, "interface pin",
                        "%s interface %s".formatted(exporter, pin.getKey())), e);
            }
        }
        return Map.copyOf(parsed);
    }

    private static int ifIndex(final String exporter, final Object key) {
        if (key instanceof Integer index) {
            return requireUsableIfIndex(exporter, index);
        }
        if (key instanceof String text) {
            if (!CANONICAL_IF_INDEX.matcher(text).matches()) {
                // no leading zeros, sign or padding: "010" would mean 10 here and 8
                // unquoted, and a spelling that changes meaning is the typo class this
                // loader refuses everywhere else
                throw new IllegalStateException(
                        ("Exporter '%s' has an interface key '%s': write the ifIndex as plain decimal digits, "
                                + "with no sign, padding or leading zeros.").formatted(exporter, text));
            }
            try {
                return requireUsableIfIndex(exporter, Integer.parseInt(text));
            } catch (final NumberFormatException e) {
                throw new IllegalStateException(
                        "Exporter '%s' has an interface key '%s' outside the ifIndex range."
                                .formatted(exporter, text), e);
            }
        }
        if (key instanceof Number) {
            // a Long or BigInteger key is a whole number, just not one an ifIndex can be
            throw new IllegalStateException(
                    "Exporter '%s' has an interface key '%s' outside the ifIndex range."
                            .formatted(exporter, key));
        }
        throw new IllegalStateException(
                "Exporter '%s' has an interface key '%s' that is not a whole number.".formatted(exporter, key));
    }

    private static int requireUsableIfIndex(final String exporter, final int ifIndex) {
        if (ifIndex <= 0) {
            // enrichment treats ifIndex 0 as "unknown interface" and skips the whole
            // ladder, so a pin there could never apply: a typo, not dead configuration
            throw new IllegalStateException(
                    "Exporter '%s' pins interface %d, which is not a usable ifIndex: it must be positive."
                            .formatted(exporter, ifIndex));
        }
        return ifIndex;
    }

    private static InterfacePin interfacePin(final String exporter, final int ifIndex, final Object value,
                                             final List<String> warnings) {
        final Map<String, Object> body = pinBody(exporter, ifIndex, value);
        final InterfacePin pin = new InterfacePin(
                pinText(exporter, ifIndex, "name", body.get("name")),
                pinText(exporter, ifIndex, "alias", body.get("alias")),
                highSpeed(exporter, ifIndex, body.get("high-speed")));
        if (pin.name() == null && pin.alias() == null && pin.highSpeed() == null) {
            // the agent-range precedent: an entry that declares nothing is a
            // half-finished edit, and silence about it is how it stays that way
            warnings.add(("Exporter '%s' interface %d pins nothing: give it a name, alias or high-speed, "
                    + "or remove the entry").formatted(exporter, ifIndex));
        }
        return pin;
    }

    private static Map<String, Object> pinBody(final String exporter, final int ifIndex, final Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Exporter '%s' interface %d must be a mapping, found %s."
                            .formatted(exporter, ifIndex, value.getClass().getSimpleName()));
        }
        final Map<String, Object> body = new LinkedHashMap<>(map.size() * 2);
        for (final Map.Entry<?, ?> field : map.entrySet()) {
            if (!(field.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        "Exporter '%s' interface %d has a non-string key '%s'; quote it."
                                .formatted(exporter, ifIndex, field.getKey()));
            }
            if (!PIN_KEYS.contains(key)) {
                throw new IllegalStateException(
                        "Exporter '%s' interface %d has an unknown key '%s'; known keys are %s."
                                .formatted(exporter, ifIndex, key, new TreeSet<>(PIN_KEYS)));
            }
            body.put(key, field.getValue());
        }
        return body;
    }

    /**
     * Pinned text must be written as text. Coercing whatever YAML resolved would pin
     * {@code on} as "true" and a bare date as a JVM-formatted timestamp that differs
     * by host timezone, and a pin outranks the walk, so the wrong value would win.
     */
    private static String pinText(final String exporter, final int ifIndex, final String field, final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException(
                    "Exporter '%s' interface %d has a %s that is not text ('%s'); quote it."
                            .formatted(exporter, ifIndex, field, value));
        }
        if (text.isBlank()) {
            // only null falls through to the rungs below, so a blank would pin emptiness
            // over whatever the walk found
            throw new IllegalStateException(
                    "Exporter '%s' interface %d has a blank %s: remove the key to fall back to SNMP."
                            .formatted(exporter, ifIndex, field));
        }
        return text;
    }

    private static Long highSpeed(final String exporter, final int ifIndex, final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            // the observation-domain precedent: a quoted "1000" must fail rather than
            // bind as something that looks numeric to an operator reading the file
            throw new IllegalStateException(
                    "Exporter '%s' interface %d has a high-speed '%s' that is not a whole number."
                            .formatted(exporter, ifIndex, value));
        }
        final long speed = ((Number) value).longValue();
        if (speed <= 0 || speed > MAX_HIGH_SPEED) {
            // ifHighSpeed is a Gauge32 of Mbit/s; a non-positive or oversized pin would
            // reach utilization maths as a divide-by-zero or an overflow
            throw new IllegalStateException(
                    "Exporter '%s' interface %d has a high-speed %d Mbit/s outside 1..%d."
                            .formatted(exporter, ifIndex, speed, MAX_HIGH_SPEED));
        }
        return speed;
    }

    private static Long observationDomain(final String exporter, final Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalStateException(
                    "Exporter '%s' observation-domain '%s' is not a whole number.".formatted(exporter, value));
        }
        final long domain = ((Number) value).longValue();
        if (domain < 0 || domain > MAX_OBSERVATION_DOMAIN) {
            throw new IllegalStateException(
                    "Exporter '%s' observation-domain %d is outside the unsigned 32-bit range."
                            .formatted(exporter, domain));
        }
        return domain;
    }

    /**
     * Strict-from-birth address parsing: a host address or (for ranges) a CIDR
     * prefix block, in canonical spelling only. No legacy shapes, no inet_aton
     * surprises, no silent dead entries.
     */
    private static IPAddressString strictAddress(final String value, final String what, final boolean hostOnly) {
        try {
            return StrictAddresses.parse(value, hostOnly);
        } catch (final IllegalArgumentException e) {
            // the diagnosis clause completes the entry-naming sentence, so the operator
            // reads which entry, which rule, and the exact string to write instead
            throw new IllegalStateException("The %s '%s' %s".formatted(what, value, e.getMessage()));
        }
    }
}
