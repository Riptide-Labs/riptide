/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IPAddressStringParameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a legacy configuration into the two 0.9 documents, plus the summary of what changed.
 *
 * <p>The conversion splits every legacy node in half. {@code riptide.nodes.<name>} carried
 * matching, naming and credentials in one entry; 0.9 keeps how-to-talk-to-it in an agent
 * range and what-to-call-it in an enrichment entry. The split is why a node can end up
 * disabled for polling and still named: the FR-9 security rule is about sending a cleartext
 * community to addresses nobody enumerated, not about labelling flows that already arrived.
 *
 * <p>Range-scoped nodes keep their names. Story 2.7.1 gave enrichment entries prefix
 * matching, so a {@code /16} node becomes a {@code /16} enrichment entry rather than a
 * reported loss. Before that story there was nowhere to put it.
 *
 * <p>Output is valid by construction (AD-13): everything emitted here is shaped to pass
 * {@code InventoryLoader} and {@code SnmpProfilesConfig}, and the round-trip test proves it
 * rather than trusting this comment.
 */
public final class LegacyConverter {

    /** The same strictness the 0.9 loader applies, so a bad address fails here with context. */
    private static final IPAddressStringParameters STRICT_ADDRESSES = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allow_inet_aton(false)
            .getIPv4AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .getIPv6AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .toParams();

    private static final int DEFAULT_PORT = 161;
    private static final int DEFAULT_TIMEOUT_MS = 500;
    private static final int DEFAULT_RETRIES = 1;

    private LegacyConverter() {
    }

    /**
     * The conversion result. Two documents because 0.9 splits them across two files: credential
     * sets and polling profiles are Spring-bound from the main config, while agent ranges and
     * enrichment entries are direct-parsed from the inventory file. One document carrying all
     * four keys would be valid in neither place.
     */
    public record Converted(String mainConfig, String inventory, List<String> summary) {
    }

    public static Converted convert(final LegacyConfigReader.LegacyConfig legacy) {
        final Credentials credentials = new Credentials();
        final Profiles profiles = new Profiles(legacy);

        final StringBuilder agents = new StringBuilder();
        final StringBuilder exporters = new StringBuilder();
        final List<String> summary = new ArrayList<>();
        int disabled = 0;
        int ranges = 0;

        // sorted, not map order: the same input has to convert to byte-identical output, and
        // a diff between two runs of the same file would be unreviewable
        for (final LegacyNode node : new TreeMap<>(legacy.nodes()).values()) {
            final IPAddress address = strictAddress(node);
            if (node.snmp() != null) {
                ranges++;
                final boolean carveOut = node.snmp().cleartext() && address.isMultiple();
                // registered even for a carve-out. The set is unreferenced and harmless, and
                // dropping it would delete the operator's community reference from their
                // configuration entirely: re-enabling after enumerating the devices should be
                // adding an address, not going back to the 0.8 file to find the secret again
                final String credentialName = credentials.nameFor(node.snmp());
                if (carveOut) {
                    disabled++;
                    summary.add(("Disabled 1 range ('%s', %s): %s credentials on a range wider than "
                            + "one address. Its flows are still named; only polling stops.")
                            .formatted(node.name(), node.subnetAddress(), node.snmp().version()));
                }
                agents.append(agentRange(node, credentialName, profiles, carveOut));
            }
            exporters.append(exporterEntry(node));
        }

        summary.addFirst(("Converted %d node(s): %d credential set(s), %d polling profile(s), "
                + "%d agent range(s), %d enrichment entry/entries.")
                .formatted(legacy.nodes().size(), credentials.names.size(), profiles.emitted.size(),
                        ranges, legacy.nodes().size()));
        if (disabled > 0) {
            summary.add("Re-enable a disabled range by enumerating its devices as single addresses, "
                    + "or by moving the segment to v3. Both are in the comment above each entry.");
        }
        return new Converted(mainConfig(credentials, profiles), inventory(agents, exporters), summary);
    }

    private static IPAddress strictAddress(final LegacyNode node) {
        final IPAddress address = new IPAddressString(node.subnetAddress(), STRICT_ADDRESSES).getAddress();
        final boolean host = address != null && !address.isMultiple() && !address.isPrefixed();
        final boolean block = address != null && address.isPrefixed() && address.isSinglePrefixBlock();
        if (!host && !block) {
            throw new IllegalStateException(
                    ("Node '%s' has subnet-address '%s', which 0.9 does not accept: it must be a host "
                            + "address or a CIDR prefix with no host bits set, written without "
                            + "leading zeros. Fix it in the legacy file and convert again.")
                            .formatted(node.name(), node.subnetAddress()));
        }
        return address;
    }

    private static String agentRange(final LegacyNode node, final String credentialName,
                                     final Profiles profiles, final boolean carveOut) {
        final StringBuilder out = new StringBuilder();
        if (carveOut) {
            out.append("      # Range '").append(node.name()).append("' used ")
                    .append(node.snmp().version()).append(" credentials. Disabled: the cleartext\n")
                    .append("      # community would be sent to any in-range address that emits a flow.\n")
                    .append("      # Either enumerate the devices as single addresses, or migrate the\n")
                    .append("      # segment to v3. Its credentials are kept as '")
                    .append(credentialName).append("' so re-enabling\n")
                    .append("      # is one line. (FR-9)\n");
        }
        out.append("      ").append(quote(node.subnetAddress())).append(":\n");
        if (carveOut) {
            // no credentials key at all: the width rule fires regardless of `enabled`, so a
            // parked reference would emit a config that cannot start
            out.append("        enabled: false\n");
            return out.toString();
        }
        out.append("        credentials: ").append(credentialName).append('\n');
        final String profile = profiles.nameFor(node.snmp());
        if (profile != null) {
            out.append("        polling: ").append(profile).append('\n');
        }
        final Integer port = node.snmp().port();
        if (port != null && port != DEFAULT_PORT) {
            out.append("        port: ").append(port).append('\n');
        }
        return out.toString();
    }

    private static String exporterEntry(final LegacyNode node) {
        final StringBuilder out = new StringBuilder();
        out.append("    ").append(quote(node.name())).append(":\n")
                .append("      address: ").append(quote(node.subnetAddress())).append('\n');
        if (node.observationDomain() != null) {
            out.append("      observation-domain: ").append(node.observationDomain()).append('\n');
        }
        final Map<Integer, LegacyNode.LegacyPin> pins = new TreeMap<>(node.interfaces());
        pins.values().removeIf(LegacyNode.LegacyPin::pinsNothing);
        if (!pins.isEmpty()) {
            out.append("      interfaces:\n");
            for (final Map.Entry<Integer, LegacyNode.LegacyPin> pin : pins.entrySet()) {
                // quoted canonical decimal: unquoted 010 is octal 8 under YAML 1.1, and 2.7
                // made the quoted form the only spelling the loader accepts unambiguously
                out.append("        \"").append(pin.getKey()).append("\":\n");
                appendPinField(out, "name", pin.getValue().name());
                appendPinField(out, "alias", pin.getValue().alias());
                if (pin.getValue().highSpeed() != null) {
                    out.append("          high-speed: ").append(pin.getValue().highSpeed()).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static void appendPinField(final StringBuilder out, final String key, final String value) {
        if (value != null) {
            out.append("          ").append(key).append(": ").append(quote(value)).append('\n');
        }
    }

    private static String mainConfig(final Credentials credentials, final Profiles profiles) {
        final StringBuilder out = new StringBuilder();
        out.append("# Generated by 'riptide convert'. This half belongs in the main configuration\n")
                .append("# (application.yaml), not in the inventory file.\n")
                .append("riptide:\n")
                .append("  snmp:\n")
                .append("    credentials:\n");
        for (final Map.Entry<String, LegacyNode.LegacySnmp> set : credentials.byName().entrySet()) {
            out.append("      ").append(set.getKey()).append(":\n")
                    .append("        snmp-version: ").append(quote(set.getValue().version())).append('\n');
            appendIfPresent(out, "community", set.getValue().community());
            appendIfPresent(out, "security-name", set.getValue().securityName());
            appendIfPresent(out, "auth-protocol", set.getValue().authProtocol());
            appendIfPresent(out, "auth-passphrase", set.getValue().authPassphrase());
            appendIfPresent(out, "priv-protocol", set.getValue().privProtocol());
            appendIfPresent(out, "priv-passphrase", set.getValue().privPassphrase());
        }
        if (!profiles.emitted.isEmpty()) {
            out.append("    polling:\n");
            for (final Map.Entry<String, Profile> profile : profiles.emitted.entrySet()) {
                out.append("      ").append(profile.getKey()).append(":\n");
                if (profile.getValue().refresh() != null) {
                    out.append("        refresh-interval: ").append(profile.getValue().refresh()).append('\n');
                }
                if (profile.getValue().expiry() != null) {
                    out.append("        snapshot-expiry: ").append(profile.getValue().expiry()).append('\n');
                }
                out.append("        timeout: ").append(profile.getValue().timeout()).append('\n')
                        .append("        retries: ").append(profile.getValue().retries()).append('\n');
            }
        }
        return out.toString();
    }

    private static void appendIfPresent(final StringBuilder out, final String key, final String value) {
        if (value != null) {
            out.append("        ").append(key).append(": ").append(quote(value)).append('\n');
        }
    }

    private static String inventory(final StringBuilder agents, final StringBuilder exporters) {
        final StringBuilder out = new StringBuilder();
        out.append("# Generated by 'riptide convert'. This half belongs in the inventory file\n")
                .append("# named by riptide.inventory.file.\n")
                .append("riptide:\n");
        if (!agents.isEmpty()) {
            out.append("  snmp:\n    agents:\n").append(agents);
        }
        out.append("  exporters:\n").append(exporters);
        return out.toString();
    }

    /**
     * Double-quoted always. Names and secret references are operator input: a node called
     * {@code on}, an alias that reads as a date, or an address-shaped key all change meaning
     * unquoted under YAML 1.1, and a value containing a quote would otherwise emit a file that
     * does not parse.
     */
    private static String quote(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Deduplicates identical credential blocks, naming them in first-seen sorted order. */
    private static final class Credentials {
        private final Map<LegacyNode.LegacySnmp, String> names = new LinkedHashMap<>();

        private String nameFor(final LegacyNode.LegacySnmp snmp) {
            return this.names.computeIfAbsent(snmp.credentialsOnly(),
                    key -> "credentials-" + (this.names.size() + 1));
        }

        private Map<String, LegacyNode.LegacySnmp> byName() {
            final Map<String, LegacyNode.LegacySnmp> out = new LinkedHashMap<>();
            this.names.forEach((snmp, name) -> out.put(name, snmp));
            return out;
        }
    }

    private record Profile(String refresh, String expiry, int timeout, int retries) {
    }

    /**
     * Polling profiles, which exist for two reasons that meet here: the legacy global poll
     * cadence, and per-node timeout/retries. 0.9 keeps all three on the profile, so two nodes
     * sharing credentials but differing in timeout need one credential set and two profiles.
     */
    private static final class Profiles {
        private final Map<Profile, String> names = new LinkedHashMap<>();
        private final Map<String, Profile> emitted = new LinkedHashMap<>();
        private final String refresh;
        private final String expiry;

        private Profiles(final LegacyConfigReader.LegacyConfig legacy) {
            this.refresh = legacy.refreshIntervalMs() == null
                    ? null : Duration.ofMillis(legacy.refreshIntervalMs()).toString();
            this.expiry = legacy.snapshotExpiryMs() == null
                    ? null : Duration.ofMillis(legacy.snapshotExpiryMs()).toString();
        }

        /**
         * The profile name for this node, or {@code null} when it needs none.
         *
         * <p>A node with default timeout and retries and no global cadence override needs no
         * profile at all: the loader's built-in default already carries exactly those values, so
         * emitting one would be noise the operator has to read past.</p>
         */
        private String nameFor(final LegacyNode.LegacySnmp snmp) {
            final int timeout = snmp.timeout() == null ? DEFAULT_TIMEOUT_MS : snmp.timeout();
            final int retries = snmp.retries() == null ? DEFAULT_RETRIES : snmp.retries();
            if (timeout == DEFAULT_TIMEOUT_MS && retries == DEFAULT_RETRIES
                    && this.refresh == null && this.expiry == null) {
                return null;
            }
            final Profile profile = new Profile(this.refresh, this.expiry, timeout, retries);
            final String existing = this.names.get(profile);
            if (existing != null) {
                return existing;
            }
            // the global cadence with untouched timeout/retries IS the legacy default, so it
            // takes the name the loader already treats as the fallback for every range
            final String name = timeout == DEFAULT_TIMEOUT_MS && retries == DEFAULT_RETRIES
                    ? "default"
                    : "polling-" + (this.names.size() + 1);
            this.names.put(profile, name);
            this.emitted.put(name, profile);
            return name;
        }
    }
}
