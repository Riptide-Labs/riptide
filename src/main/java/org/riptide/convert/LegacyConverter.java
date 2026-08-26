/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.riptide.inventory.StrictAddresses;

import inet.ipaddr.IPAddress;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.PollingProfile;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

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

    private static final int DEFAULT_PORT = 161;
    private static final int MAX_PORT = 65535;
    private static final long MAX_OBSERVATION_DOMAIN = 0xFFFF_FFFFL;
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

        // legacy nodes could share an address and be told apart by observation-domain, which
        // NodeRegistry validated on the pair. 0.9 agent ranges have no domain concept, so two
        // such nodes collapse onto one range key: emitting both produces a duplicate YAML key
        // and an inventory that cannot load, reported against a generated line number
        final Map<String, String> rangeOwners = new LinkedHashMap<>();
        // the FILE's spelling behind each claimed range key: a collision surfacing only
        // after a zone-strip or netmask rewrite must name what the operator can grep
        // for — the summary line explaining the rewrite is discarded by the throw
        final Map<String, String> rangeSpellings = new LinkedHashMap<>();
        // every polled range, and the domain-pinned subset of it. Nesting cannot be decided inside
        // the loop: it is a question about the whole set, and the covering range may sort later
        final Map<String, PolledRange> polledRanges = new LinkedHashMap<>();
        final Map<String, LegacyNode> pinnedPolled = new LinkedHashMap<>();

        // sorted, not map order: the same input has to convert to byte-identical output, and
        // a diff between two runs of the same file would be unreviewable
        for (final LegacyNode raw : new TreeMap<>(legacy.nodes()).values()) {
            // 0.8 accepted zoned spellings and its matcher was equally zone-blind, so the
            // zone is stripped rather than refused (#553) — same charter as the netmask
            // translation below. A spelling combining zone AND netmask matches neither
            // translation and is refused with the combined diagnosis
            final LegacyNode dezoned = StrictAddresses
                    .zoneStrippedForm(raw.subnetAddress())
                    .map(stripped -> {
                        summary.add(("Rewrote node '%s' subnet-address '%s' to '%s' (zone ids are "
                                + "ignored in matching).")
                                .formatted(raw.name(), raw.subnetAddress(), stripped));
                        return new LegacyNode(raw.name(), stripped, raw.observationDomain(),
                                raw.snmp(), raw.interfaces());
                    })
                    .orElse(raw);
            // 0.8 accepted the contiguous-netmask spelling and this tool's charter is a
            // mechanical rewrite, so it is translated to CIDR rather than refused — the
            // node is rewritten up front so every downstream use (emission, dedup,
            // messages) sees one spelling. A non-contiguous mask still fails: it has no
            // CIDR equivalent and 0.8 silently mis-read it (#538)
            final LegacyNode node = StrictAddresses
                    .cidrFormOfContiguousNetmask(dezoned.subnetAddress())
                    .map(cidr -> {
                        summary.add("Rewrote node '%s' subnet-address '%s' to the CIDR form '%s'."
                                .formatted(dezoned.name(), dezoned.subnetAddress(), cidr));
                        return new LegacyNode(dezoned.name(), cidr, dezoned.observationDomain(),
                                dezoned.snmp(), dezoned.interfaces());
                    })
                    .orElse(dezoned);
            final IPAddress address = strictAddress(node);
            requireEmittableNode(node);
            if (node.snmp() != null) {
                final String canonical = address.toCanonicalString();
                final String owner = rangeOwners.putIfAbsent(canonical, node.name());
                if (owner != null) {
                    throw new IllegalStateException(
                            ("Nodes '%s' and '%s' are both polled at %s. 0.8 told them apart by "
                                    + "observation-domain, which 0.9 agent ranges do not carry, so they "
                                    + "would become one range. Merge them, or drop the snmp block from "
                                    + "one: their names both survive as enrichment entries either way.%s%s")
                                    .formatted(owner, node.name(), node.subnetAddress(),
                                            respelled(owner, rangeSpellings.get(canonical), node.subnetAddress()),
                                            respelled(node.name(), raw.subnetAddress(), node.subnetAddress())));
                }
                rangeSpellings.put(canonical, raw.subnetAddress());
                ranges++;
                final boolean carveOut = node.snmp().cleartext() && address.isMultiple();
                polledRanges.put(node.name(), new PolledRange(address, carveOut));
                if (node.observationDomain() != null) {
                    pinnedPolled.put(node.name(), node);
                }
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

        reportPinnedNodesOverlappingAnotherRange(pinnedPolled, polledRanges, summary);

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

    /**
     * Builds the 0.9 objects this node will become and validates them, before a byte is
     * emitted.
     *
     * <p>This is what makes AD-13 a property rather than a claim. The legacy tree accepted
     * shapes 0.9 rejects — a v3 set with no security-name, auth without a passphrase, priv
     * without auth, a v2c set carrying leftover v3 fields, a non-positive timeout, a port
     * outside the legal range — and every one of them converted cleanly and then failed the
     * operator's next startup. Failing here names the node, which is a thing in their file;
     * failing at boot names a generated credential set, which is not.</p>
     */
    private static void requireEmittableNode(final LegacyNode node) {
        if (node.observationDomain() != null
                && (node.observationDomain() < 0 || node.observationDomain() > MAX_OBSERVATION_DOMAIN)) {
            throw new IllegalStateException(
                    ("Node '%s' has observation-domain %d, outside the unsigned 32-bit range 0.9 "
                            + "accepts.").formatted(node.name(), node.observationDomain()));
        }
        node.interfaces().forEach((ifIndex, pin) -> {
            requireNonBlank(node, ifIndex, "name", pin.name());
            requireNonBlank(node, ifIndex, "alias", pin.alias());
        });
        if (node.snmp() == null) {
            return;
        }
        final LegacyNode.LegacySnmp snmp = node.snmp();
        if (snmp.port() != null && (snmp.port() < 1 || snmp.port() > MAX_PORT)) {
            throw new IllegalStateException(
                    "Node '%s' has snmp port %d, outside 1..%d.".formatted(node.name(), snmp.port(), MAX_PORT));
        }
        // the real records, validated by their own contract: a shape check written here would
        // drift from the one that actually runs at startup
        credentialSet(node).validate("the credentials of node '" + node.name() + "'");
        new PollingProfile(Duration.ofMinutes(10), Duration.ofMinutes(30),
                snmp.timeout() == null ? DEFAULT_TIMEOUT_MS : snmp.timeout(),
                snmp.retries() == null ? DEFAULT_RETRIES : snmp.retries())
                .validate("the polling settings of node '" + node.name() + "'");
    }

    private static void requireNonBlank(final LegacyNode node, final int ifIndex,
                                        final String field, final String value) {
        if (value != null && value.isBlank()) {
            throw new IllegalStateException(
                    ("Node '%s' interface %d has a blank %s. 0.9 rejects it, because only an absent "
                            + "pin falls through to the rungs below: remove the key.")
                            .formatted(node.name(), ifIndex, field));
        }
    }

    /** The legacy snmp block as the {@link CredentialSet} it will be emitted as. */
    private static CredentialSet credentialSet(final LegacyNode node) {
        final LegacyNode.LegacySnmp snmp = node.snmp();
        final CredentialVersion version;
        try {
            version = CredentialVersion.valueOf(snmp.version().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException(
                    ("Node '%s' has snmp-version '%s'; 0.9 accepts v1, v2c and v3.")
                            .formatted(node.name(), snmp.version()), e);
        }
        return new CredentialSet(version,
                snmp.community() == null ? null : SecretRef.of(snmp.community()),
                snmp.securityName(),
                authProtocol(node, snmp.authProtocol()),
                snmp.authPassphrase() == null ? null : SecretRef.of(snmp.authPassphrase()),
                privProtocol(node, snmp.privProtocol()),
                snmp.privPassphrase() == null ? null : SecretRef.of(snmp.privPassphrase()));
    }

    private static TargetBuilder.AuthProtocol authProtocol(final LegacyNode node, final String value) {
        if (value == null) {
            return null;
        }
        try {
            return TargetBuilder.AuthProtocol.valueOf(value);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException(
                    ("Node '%s' has auth-protocol '%s', which is not one of %s.")
                            .formatted(node.name(), value,
                                    java.util.Arrays.toString(TargetBuilder.AuthProtocol.values())), e);
        }
    }

    private static TargetBuilder.PrivProtocol privProtocol(final LegacyNode node, final String value) {
        if (value == null) {
            return null;
        }
        try {
            return TargetBuilder.PrivProtocol.valueOf(value);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException(
                    ("Node '%s' has priv-protocol '%s', which is not one of %s.")
                            .formatted(node.name(), value,
                                    java.util.Arrays.toString(TargetBuilder.PrivProtocol.values())), e);
        }
    }

    /** Names a collided node's FILE spelling when a rewrite hid it from the message. */
    private static String respelled(final String name, final String fileSpelling, final String printed) {
        return fileSpelling == null || fileSpelling.equals(printed)
                ? ""
                : " Node '%s' spells it '%s' in the file.".formatted(name, fileSpelling);
    }

    private static IPAddress strictAddress(final LegacyNode node) {
        try {
            // the loader's parser, not a copy of it: the converter's whole audience is
            // operators pasting 0.8 configs full of exactly the spellings the diagnosis
            // ladder names (netmasks, leading zeros, inet_aton shorthand)
            return StrictAddresses.parse(node.subnetAddress(), false).getAddress();
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException(
                    ("Node '%s' has subnet-address '%s', which 0.9 does not accept: it %s "
                            + "Fix it in the legacy file and convert again.")
                            .formatted(node.name(), node.subnetAddress(), e.getMessage()));
        }
    }

    /** A polled range as the report needs it: its extent, and whether FR-9 disabled it. */
    private record PolledRange(IPAddress address, boolean carveOut) {
    }

    /** Matches {@code PinnedPrefixMatcher.rankOf}: a longer prefix is more specific. */
    private static int specificity(final IPAddress address) {
        final Integer prefix = address.getNetworkPrefixLength();
        return prefix != null ? prefix : address.getBitCount();
    }

    /**
     * Reports every domain-pinned polled node that overlaps another polled range (#615).
     *
     * <p>The conversion is correct and is not refused: 0.9 resolves the pair by longest prefix, the
     * same rule the exporter tree uses. But it resolves it <em>differently from 0.8</em>, and the
     * conversion is the one moment the operator is looking at both configurations.</p>
     *
     * <p>What 0.8 did is worth stating precisely, because it reads as a lost capability and is not
     * one. The poller held one registration per address — {@code Map<InetSocketAddress,
     * Registration>}, unchanged since 0.8 — and its {@code register()} returned the existing
     * registration on collision, discarding the newly resolved endpoint. So a device covered by both
     * a pinned node and another polled one was polled with whichever credentials the first flow after
     * boot happened to select, re-decided on every restart. 0.9 replaced a race with a rule.</p>
     *
     * <p><b>Overlap, not containment, and in both directions.</b> A first version tested only
     * "pinned range inside another", which misses the reverse: {@code PinnedPrefixMatcher} consults
     * the pinned pool first, so a pinned <em>wider</em> range beats an unpinned narrower one for its
     * own domain while the narrower one serves every other domain. That is the same two-endpoint race
     * and it went unreported — exactly the population this exists to warn. For prefixes, overlapping
     * means one contains the other, so both directions are tested.</p>
     *
     * <p>The fall-through named is the <em>most specific</em> overlapping range, not the first one
     * found. Insertion order here is sorted node name, which has nothing to do with specificity, so
     * breaking on the first pointed operators at the wrong credentials whenever a pinned node sat
     * inside more than one range.</p>
     *
     * <p>A carve-out is called out rather than described as polling. An FR-9 range is emitted
     * {@code enabled: false} with no credentials, and it still wins the match, so it shadows the
     * wider range instead of deferring to it: those addresses are polled by <em>nothing</em>. Saying
     * "polled with its credentials" there is false, and v2c on a subnet is the commonest legacy
     * shape.</p>
     */
    private static void reportPinnedNodesOverlappingAnotherRange(final Map<String, LegacyNode> pinnedPolled,
                                                                 final Map<String, PolledRange> polledRanges,
                                                                 final List<String> summary) {
        for (final Map.Entry<String, LegacyNode> pinned : pinnedPolled.entrySet()) {
            final PolledRange self = polledRanges.get(pinned.getKey());

            String otherName = null;
            PolledRange other = null;
            for (final Map.Entry<String, PolledRange> candidate : polledRanges.entrySet()) {
                if (candidate.getKey().equals(pinned.getKey())) {
                    continue;
                }
                final IPAddress extent = candidate.getValue().address();
                if (!extent.contains(self.address()) && !self.address().contains(extent)) {
                    continue;
                }
                if (other == null || specificity(extent) > specificity(other.address())) {
                    otherName = candidate.getKey();
                    other = candidate.getValue();
                }
            }
            if (other == null) {
                continue;
            }

            // over the addresses both cover, 0.9 gives the more specific range
            final boolean selfWins = specificity(self.address()) >= specificity(other.address());
            final String winnerName = selfWins ? pinned.getKey() : otherName;
            final PolledRange winner = selfWins ? self : other;

            summary.add(("Node '%s' (%s) pins observation-domain %d and overlaps polled range '%s' "
                    + "(%s). In 0.8 a flow on domain %d resolved credentials from '%s' and a flow on "
                    + "any other domain from '%s', so which of the two polled the device depended on "
                    + "which arrived first after start-up. In 0.9 the domain is not consulted and the "
                    + "most specific range decides: %s Naming is unchanged — the pin still decides "
                    + "exporterName and interface pins.")
                    .formatted(pinned.getKey(), pinned.getValue().subnetAddress(),
                            pinned.getValue().observationDomain(), otherName,
                            other.address().toCanonicalString(),
                            pinned.getValue().observationDomain(), pinned.getKey(), otherName,
                            winner.carveOut()
                                    ? ("the addresses both cover are polled by nothing, because '"
                                            + winnerName + "' is emitted disabled and still wins the "
                                            + "match rather than deferring to the wider range.")
                                    : ("the addresses both cover are polled with '" + winnerName
                                            + "' credentials.")));
        }
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
                .append("# (application.yaml), not in the inventory file.\n");
        if (credentials.names.isEmpty() && profiles.emitted.isEmpty()) {
            // a label-only legacy file polls nothing, so this half has no content. Saying so
            // beats handing the operator a file whose whole body is an empty mapping
            return out.append("# Nothing to add here: no node in the legacy file was polled.\n")
                    .toString();
        }
        out.append("riptide:\n").append("  snmp:\n");
        if (!credentials.names.isEmpty()) {
            out.append("    credentials:\n");
        }
        for (final Map.Entry<String, LegacyNode.LegacySnmp> set : credentials.byName().entrySet()) {
            out.append("      ").append(set.getKey()).append(":\n")
                    // 'version', not 'snmp-version': that is the CredentialSet record component,
                    // and @ConfigurationProperties ignores unknown fields, so the wrong spelling
                    // bound to nothing and failed startup with "has no version"
                    .append("        version: ").append(quote(set.getValue().version())).append('\n');
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
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        // a raw control character makes the emitted file unreadable to the YAML
                        // reader; a raw newline is worse, because it folds into a space and the
                        // file parses with a silently different value
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
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
