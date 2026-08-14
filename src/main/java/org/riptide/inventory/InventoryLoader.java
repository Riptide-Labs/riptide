/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IPAddressStringParameters;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one pure function from configuration to serving state (AD-4):
 * {@code (Spring-bound profiles, inventory file) -> validated InventorySnapshot},
 * invoked identically at boot and, once the reload story lands, on every reload.
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
public final class InventoryLoader {

    private static final Set<String> ROOT_KEYS = Set.of("riptide");
    private static final Set<String> RIPTIDE_KEYS = Set.of("snmp", "exporters");
    private static final Set<String> SNMP_KEYS = Set.of("agents");
    private static final Set<String> AGENT_KEYS = Set.of("credentials", "polling");
    private static final Set<String> EXPORTER_KEYS = Set.of("address", "observation-domain");

    private static final long MAX_OBSERVATION_DOMAIN = 0xFFFF_FFFFL;

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
        final PinnedPrefixMatcher.Builder<AgentEntry> builder = PinnedPrefixMatcher.builder();
        for (final Map.Entry<String, Object> entry : agents.entrySet()) {
            final Map<String, Object> entryBody = body(entry, "agent range");
            requireEntryKeys(entry.getKey(), "agent range", entryBody, AGENT_KEYS);
            final CredentialSet credentials = resolve(entry.getKey(), "credential set",
                    entryBody.get("credentials"), profiles.credentials());
            final PollingProfile polling = resolve(entry.getKey(), "polling profile",
                    entryBody.get("polling"), profiles.polling());
            builder.add(entry.getKey(), strictAddress(entry.getKey(), "agent range", false), null,
                    new AgentEntry(entry.getKey(), credentials, polling));
        }
        return builder.build();
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
                throw new IllegalStateException(
                        "Inventory file %s: unknown key '%s' under %s; known keys are %s."
                                .formatted(sourceName, key, where, known));
            }
        }
    }

    private static void requireEntryKeys(final String name, final String kind,
                                         final Map<String, Object> entryBody, final Set<String> known) {
        for (final String key : entryBody.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalStateException(
                        "The %s '%s' has an unknown key '%s'; known keys are %s."
                                .formatted(kind, name, key, known));
            }
        }
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
