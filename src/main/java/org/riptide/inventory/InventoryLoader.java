/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IPAddressStringParameters;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private static final Set<String> AGENT_KEYS = Set.of("credentials", "polling", "enabled");
    private static final Set<String> EXPORTER_KEYS = Set.of("address", "observation-domain", "interfaces");

    private static final Set<String> PIN_KEYS = Set.of("name", "alias", "high-speed");

    private static final long MAX_OBSERVATION_DOMAIN = 0xFFFF_FFFFL;

    /** ifHighSpeed is a Gauge32 in Mbit/s. */
    private static final long MAX_HIGH_SPEED = 0xFFFF_FFFFL;

    private static final java.util.regex.Pattern CANONICAL_IF_INDEX = java.util.regex.Pattern.compile("[1-9][0-9]*");

    // bounded diagnostics: name a readable number of half-finished entries, then count
    private static final int MAX_NAMED_EMPTY_ENTRIES = 20;

    // roughly 700k entries; generous but finite, so a runaway generated file is a
    // named error instead of an OOM
    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    /** Checked before the read, because the code-point limit only bounds the parser. */
    private static final long MAX_FILE_BYTES = CODE_POINT_LIMIT;

    /**
     * No inet_aton joins, no leading zeros, no empty or all-address forms: a typo
     * like "10.0.1" or "010.0.0.7" must fail, not quietly mean a different address.
     */
    private static final IPAddressStringParameters STRICT_ADDRESSES = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allow_inet_aton(false)
            .getIPv4AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .getIPv6AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .toParams();

    private InventoryLoader() {
    }

    /**
     * Loads and validates the inventory. A {@code null} file means an empty
     * inventory, which is valid; a set but unreadable file is an error naming the
     * problem.
     */
    public static InventorySnapshot load(final SnmpProfilesConfig profiles, final Path file) {
        if (file == null) {
            return InventorySnapshot.empty();
        }
        requireReadableSize(file);
        final String content;
        try {
            content = Files.readString(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        return parse(profiles, content, file.toString());
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

    /** The pure core: parses and validates inventory content into a snapshot. */
    public static InventorySnapshot parse(final SnmpProfilesConfig profiles, final String content,
                                          final String sourceName) {
        final Map<String, Object> root = parseYaml(content, sourceName);
        requireKnownKeys(sourceName, "the file root", root, ROOT_KEYS);
        final Map<String, Object> riptide = section(root, "riptide", sourceName);
        requireKnownKeys(sourceName, "'riptide'", riptide, RIPTIDE_KEYS);
        final Map<String, Object> snmp = section(riptide, "snmp", sourceName);
        requireKnownKeys(sourceName, "'riptide.snmp'", snmp, SNMP_KEYS);
        final Map<String, Object> agents = section(snmp, "agents", sourceName);
        final Map<String, Object> exporters = section(riptide, "exporters", sourceName);

        try {
            return new InventorySnapshot(agents(profiles, agents), exporters(exporters));
        } catch (final IllegalStateException e) {
            // uniform operator experience: every entry-level error names the file,
            // including the matcher's duplicate-coverage errors
            throw new IllegalStateException(
                    "Inventory file %s: %s".formatted(sourceName, e.getMessage()), e);
        }
    }

    private static PinnedPrefixMatcher<AgentEntry> agents(final SnmpProfilesConfig profiles,
                                                          final Map<String, Object> agents) {
        // one instance per build: every range that names no profile shares it (FR-7)
        final PollingProfile defaultProfile =
                profiles.polling().getOrDefault("default", PollingProfile.builtInDefault());
        final PinnedPrefixMatcher.Builder<AgentEntry> builder = PinnedPrefixMatcher.builder();
        int declaredNothing = 0;
        for (final Map.Entry<String, Object> entry : agents.entrySet()) {
            final Map<String, Object> entryBody = body(entry, "agent range");
            requireEntryKeys(entry.getKey(), "agent range", entryBody, AGENT_KEYS);
            final boolean enabled = enabled(entry.getKey(), entryBody.get("enabled"));
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
                    log.warn("Agent range '{}' declares nothing: it still matches, so it can shadow wider ranges, "
                            + "and with no credential set it is never polled. Give it a credential set, or spell "
                            + "the exclusion as 'enabled: false' if that is what you meant", entry.getKey());
                }
            }
            builder.add(entry.getKey(), address, null,
                    new AgentEntry(entry.getKey(), credentials, polling, enabled));
        }
        if (declaredNothing > MAX_NAMED_EMPTY_ENTRIES) {
            // a generated inventory can carry thousands of these; naming every one
            // would bury the rest of startup (the bounded-diagnostic idiom)
            log.warn("{} further agent ranges declare nothing and are listed no further",
                    declaredNothing - MAX_NAMED_EMPTY_ENTRIES);
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

    private static PinnedPrefixMatcher<ExporterEntry> exporters(final Map<String, Object> exporters) {
        final PinnedPrefixMatcher.Builder<ExporterEntry> builder = PinnedPrefixMatcher.builder();
        for (final Map.Entry<String, Object> entry : exporters.entrySet()) {
            final Map<String, Object> entryBody = body(entry, "exporter");
            requireEntryKeys(entry.getKey(), "exporter", entryBody, EXPORTER_KEYS);
            final Object address = entryBody.get("address");
            if (address == null) {
                throw new IllegalStateException(
                        "Exporter '%s' has no address — every enrichment entry needs one.".formatted(entry.getKey()));
            }
            // prefixes allowed, like agent ranges: an entry may label and pin a whole
            // subnet, which is how a site-scoped label survives the move off the legacy
            // tree. Most specific still wins, so a bare host beats a prefix covering it
            final IPAddressString parsedAddress = strictAddress(String.valueOf(address),
                    "exporter '%s' address".formatted(entry.getKey()), false);
            final Long pin = observationDomain(entry.getKey(), entryBody.get("observation-domain"));
            builder.add(entry.getKey(), parsedAddress, pin,
                    new ExporterEntry(entry.getKey(), parsedAddress, pin,
                            interfacePins(entry.getKey(), entryBody.get("interfaces"))));
        }
        return builder.build();
    }

    private static Map<String, Object> parseYaml(final String content, final String sourceName) {
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setAllowDuplicateKeys(false);
        try {
            final Map<?, ?> root = new Yaml(options).load(content);
            return root != null ? stringKeyed(root, "the file root", sourceName) : Map.of();
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
     */
    private static Map<String, Object> stringKeyed(final Map<?, ?> raw, final String where,
                                                   final String sourceName) {
        final Map<String, Object> typed = new LinkedHashMap<>(raw.size() * 2);
        for (final Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        "Inventory file %s: key '%s' under %s is not a string — quote it."
                                .formatted(sourceName, entry.getKey(), where));
            }
            typed.put(key, entry.getValue());
        }
        return typed;
    }

    private static Map<String, Object> section(final Map<String, Object> parent, final String key,
                                               final String sourceName) {
        final Object value = parent.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Inventory file %s: '%s' must be a mapping, found %s."
                            .formatted(sourceName, key, value.getClass().getSimpleName()));
        }
        return stringKeyed(map, "'" + key + "'", sourceName);
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

    private static void requireKnownKeys(final String sourceName, final String where,
                                         final Map<String, Object> map, final Set<String> known) {
        for (final String key : map.keySet()) {
            if (!known.contains(key)) {
                // sorted: Set.of iteration order is salt-randomized, so the listed
                // keys would otherwise shuffle between runs of the same binary
                throw new IllegalStateException(
                        "Inventory file %s: unknown key '%s' under %s; known keys are %s."
                                .formatted(sourceName, key, where, new TreeSet<>(known)));
            }
        }
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
     * <p>Errors fire in document order, which is what makes the first complaint about
     * a file stable across runs.</p>
     *
     * <p>One YAML wart survives and cannot be seen from here: an unquoted {@code 010}
     * is resolved to 8 by YAML 1.1 octal rules before the loader is handed the key.
     * Quote it, or write it without the leading zero.</p>
     */
    private static Map<Integer, InterfacePin> interfacePins(final String exporter, final Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> pins)) {
            throw new IllegalStateException(
                    "Exporter '%s' interfaces must be a mapping of ifIndex to pins, found %s."
                            .formatted(exporter, value.getClass().getSimpleName()));
        }
        final Map<Integer, InterfacePin> parsed = new LinkedHashMap<>(pins.size() * 2);
        for (final Map.Entry<?, ?> pin : pins.entrySet()) {
            final int ifIndex = ifIndex(exporter, pin.getKey());
            final InterfacePin previous = parsed.putIfAbsent(ifIndex, interfacePin(exporter, ifIndex, pin.getValue()));
            if (previous != null) {
                // quoted and unquoted spellings are distinct keys to SnakeYAML, so its
                // own duplicate-key check cannot see this one
                throw new IllegalStateException(
                        ("Exporter '%s' pins interface %d twice, once quoted and once not: "
                                + "keep one spelling.").formatted(exporter, ifIndex));
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

    private static InterfacePin interfacePin(final String exporter, final int ifIndex, final Object value) {
        final Map<String, Object> body = pinBody(exporter, ifIndex, value);
        final InterfacePin pin = new InterfacePin(
                pinText(exporter, ifIndex, "name", body.get("name")),
                pinText(exporter, ifIndex, "alias", body.get("alias")),
                highSpeed(exporter, ifIndex, body.get("high-speed")));
        if (pin.name() == null && pin.alias() == null && pin.highSpeed() == null) {
            // the agent-range precedent: an entry that declares nothing is a
            // half-finished edit, and silence about it is how it stays that way
            log.warn("Exporter '{}' interface {} pins nothing: give it a name, alias or high-speed, "
                    + "or remove the entry", exporter, ifIndex);
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
        final IPAddressString parsed = new IPAddressString(value, STRICT_ADDRESSES);
        final IPAddress address = parsed.getAddress();
        final boolean host = address != null && !address.isMultiple() && !address.isPrefixed();
        final boolean block = address != null && address.isPrefixed() && address.isSinglePrefixBlock();
        if (hostOnly ? !host : !(host || block)) {
            throw new IllegalStateException(hostOnly
                    ? "The %s '%s' is not a single host address.".formatted(what, value)
                    : "The %s '%s' is not a host address or CIDR prefix.".formatted(what, value));
        }
        return parsed;
    }
}
