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
    private static final Set<String> EXPORTER_KEYS = Set.of("address", "observation-domain");

    private static final long MAX_OBSERVATION_DOMAIN = 0xFFFF_FFFFL;

    // bounded diagnostics: name a readable number of half-finished entries, then count
    private static final int MAX_NAMED_EMPTY_ENTRIES = 20;

    // roughly 700k entries; generous but finite, so a runaway generated file is a
    // named error instead of an OOM
    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

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
        final String content;
        try {
            content = Files.readString(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        return parse(profiles, content, file.toString());
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
        if (credentials.getVersion() == null) {
            // bind-time validation guarantees a version, but the set is a mutable bean
            // shared across ranges: name the range instead of surfacing a bare NPE that
            // escapes the file-naming wrapper
            throw new IllegalStateException(
                    "Agent range '%s' uses credential set '%s', which has no version.".formatted(range, reference));
        }
        // a switch expression with no default: adding a version becomes a compile error
        // here rather than a silently insecure pass
        final boolean cleartext = switch (credentials.getVersion()) {
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
                            .formatted(range, reference, credentials.getVersion().name().toLowerCase(Locale.ROOT)));
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
            final IPAddressString parsedAddress = strictAddress(String.valueOf(address),
                    "exporter '%s' address".formatted(entry.getKey()), true);
            final Long pin = observationDomain(entry.getKey(), entryBody.get("observation-domain"));
            builder.add(entry.getKey(), parsedAddress, pin,
                    new ExporterEntry(entry.getKey(), parsedAddress, pin));
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
