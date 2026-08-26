/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.riptide.snmp.SnmpPollConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads a legacy 0.8 configuration file into {@link LegacyNode}s, with its own parser.
 *
 * <p>Two rules shape everything here. Every key under {@code riptide.nodes} must be one the
 * converter knows how to map, because a silently dropped key is a silently lost device or a
 * silently lost credential — the operator would discover it as missing enrichment weeks
 * later. And both relaxed-binding spellings Spring accepted are read, because configurations
 * in the wild use either and the operator did not choose which one Spring normalised.</p>
 *
 * <p>Strictness stops at the nodes tree. A legacy file is a whole application config, so
 * {@code riptide.clickhouse}, {@code riptide.routing} and the rest are passed over: they are
 * not this converter's business and are unchanged by the upgrade.</p>
 *
 * <p><b>{@code riptide.snmp.poll} was an exception to that rule and should not become one again
 * (#614).</b> It was guarded as a unit, on the reasoning that a subtree the converter maps from
 * cannot also contain keys it is right to ignore. It can: only the two cadence keys are mapped, and
 * the fleet-level siblings — {@code pool-width}, {@code max-exporters}, {@code deregister-after},
 * the dead-endpoint backoff — bind in 0.9 exactly as they did in 0.8.</p>
 *
 * <p>What made the exception wrong is that this converter <em>emits a fragment the operator merges
 * into their own configuration</em>. It never rewrites their file, so a key it passes over does not
 * move and cannot be lost. Refusing one did not prevent a loss; it withheld the fragment entirely,
 * and the shipped upgrade guide recommends {@code max-exporters} on the same page that tells
 * operators to run this tool. Guard on what the running version binds, never on what this class
 * happens to map.</p>
 */
public final class LegacyConfigReader {

    private static final Set<String> NODE_KEYS =
            Set.of("subnetaddress", "observationdomain", "snmp", "interfaces");

    private static final Set<String> SNMP_KEYS =
            Set.of("snmpversion", "community", "securityname", "authprotocol", "authpassphrase",
                    "privprotocol", "privpassphrase", "timeout", "retries", "port");

    private static final Set<String> PIN_KEYS = Set.of("name", "alias", "highspeed");

    /**
     * The poll keys this converter <em>maps</em>: 0.8's fleet-wide cadence, which becomes the
     * {@code default} polling profile and is retired in 0.9 ({@code PollKeyMigrationCheck} fails
     * startup on either). Distinct from {@link #boundPollKeys()}, which is what 0.9 still binds.
     */
    private static final Set<String> POLL_KEYS = Set.of("refreshintervalms", "snapshotexpiryms");

    /**
     * The keys {@code riptide.snmp.poll} still binds in 0.9, derived from the model rather than
     * restated here.
     *
     * <p>Derived deliberately. A hand-written list would be a second place remembering the same
     * facts, and the failure mode is silent in the worst direction: a fleet-level key added to
     * {@link SnmpPollConfig} later would start being <em>refused</em> by this converter, blocking
     * upgrades over a key the running version accepts.</p>
     *
     * <p>{@link #POLL_KEYS} happens to be a subset today, because the two retired keys survive as
     * dead fallbacks that {@code InterfaceSnapshotPoller} still reads. The guard unions the two sets
     * anyway rather than relying on that: if those fields go, this set alone would start refusing
     * the very key the converter exists to migrate.</p>
     */
    private static Set<String> boundPollKeys() {
        return Arrays.stream(SnmpPollConfig.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .map(field -> normalize(field.getName()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Names the shape this reader wants. Both flat spellings reach an error that points away from
     * the cause, so both get told the same fact: the format is nested YAML, not the file is broken.
     */
    private static final String NESTED_YAML_HINT =
            "This converter reads nested YAML: 'riptide:' containing 'nodes:', containing the node "
                    + "name, containing 'subnet-address'. Re-indent the tree into that shape and run "
                    + "the converter against it — only the riptide.nodes tree and "
                    + "riptide.snmp.poll are read, so the rest of the file need not come with it.";

    /**
     * One offending key, preferring a riptide one.
     *
     * <p>Document order alone picks whatever came first, so a file opening with
     * {@code spring.application.name} illustrated the re-indent with a key that has nothing to do
     * with the tree being asked for.</p>
     */
    private static String flatExample(final Map<String, Object> keys) {
        return keys.keySet().stream()
                .filter(key -> key.contains("."))
                .sorted(java.util.Comparator.comparing(key -> !normalize(key).startsWith("riptide")))
                .findFirst()
                .orElse("");
    }

    /**
     * Refuses a level whose keys carry dots, rather than walking past it.
     *
     * <p>A YAML file may be flattened part-way — {@code riptide:} as a mapping whose child is
     * {@code snmp.poll.refresh-interval-ms}. Spring binds that identically to the nested form, so it
     * is a shape real configurations have. This reader looked for a child named exactly {@code snmp},
     * found none, and carried on: the fleet cadence was dropped in silence and the emitted profiles
     * took 0.9's defaults. That is the precise failure the class contract forbids.</p>
     *
     * <p>Checked only at structural levels — {@code riptide} and {@code riptide.snmp} — never over
     * node names, which are operator-chosen and may legitimately contain dots (a node named after an
     * address, say).</p>
     */
    private static void refuseFlattenedLevel(final Map<String, Object> level, final String where,
                                             final String sourceName) {
        if (level.keySet().stream().noneMatch(key -> key.contains("."))) {
            return;
        }
        throw new IllegalStateException(
                ("Legacy file %s carries '%s' flat, as dotted property names ('%s') rather than a "
                        + "nested mapping. %s")
                        .formatted(sourceName, where, flatExample(level), NESTED_YAML_HINT));
    }

    /** Matches {@code InventoryLoader}: bounds the parser on a file that may be generated. */
    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    private LegacyConfigReader() {
    }

    /** The legacy tree, plus the global poll cadence that becomes the default profile. */
    public record LegacyConfig(Map<String, LegacyNode> nodes,
                               Long refreshIntervalMs,
                               Long snapshotExpiryMs) {
    }

    public static LegacyConfig parse(final String content, final String sourceName) {
        final Map<String, Object> root = parseYaml(content, sourceName);
        final Map<String, Object> riptide = child(root, "riptide");
        if (riptide == null) {
            // A flat file parses cleanly and yields one key per line, dots and all, so the section
            // walk finds no 'riptide' child on a file whose every line begins with 'riptide.'.
            // Asking "is this a riptide configuration?" is then actively misleading (#614).
            if (root.keySet().stream().anyMatch(key -> key.contains("."))) {
                throw new IllegalStateException(
                        ("Legacy file %s carries its keys flat, as dotted property names at the "
                                + "document root ('%s'). %s")
                                .formatted(sourceName, flatExample(root), NESTED_YAML_HINT));
            }
            throw new IllegalStateException(
                    "Legacy file %s has no 'riptide' section: is this a riptide configuration?"
                            .formatted(sourceName));
        }
        refuseFlattenedLevel(riptide, "riptide", sourceName);

        final Map<String, LegacyNode> nodes = new LinkedHashMap<>();
        final Map<String, Object> rawNodes = nodeSection(riptide);
        if (rawNodes != null) {
            for (final Map.Entry<String, Object> entry : rawNodes.entrySet()) {
                if (entry.getKey().isBlank()) {
                    throw new IllegalStateException(
                            ("Legacy file %s has a node with a blank name under 'riptide.nodes'. It "
                                    + "would convert to an enrichment entry that labels flows with "
                                    + "nothing.").formatted(sourceName));
                }
                nodes.put(entry.getKey(), node(entry.getKey(), entry.getValue(), sourceName));
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    ("Legacy file %s declares no nodes under 'riptide.nodes': there is nothing to "
                            + "convert. If the nodes live in another file, convert that one.")
                            .formatted(sourceName));
        }

        // relaxed lookup like every other section, and tolerant of an empty 'snmp:' key,
        // which is a null value rather than a wrong type and bound fine in 0.8
        final Map<String, Object> snmp = child(riptide, "snmp");
        if (snmp != null) {
            refuseFlattenedLevel(snmp, "riptide.snmp", sourceName);
        }
        final Map<String, Object> poll = snmp == null ? null : child(snmp, "poll");
        Long refresh = null;
        Long expiry = null;
        if (poll != null) {
            final Map<String, Object> canonicalPoll = canonical(poll, sourceName, "riptide.snmp.poll");
            // Guarded on what 0.9 BINDS, not on what this converter maps. The two differ, and
            // treating them as one refused files over keys the upgrade leaves alone: pool-width and
            // friends are not this converter's business, exactly as riptide.clickhouse is not.
            // The old comment here reasoned that "an unmappable sibling is a dropped setting" —
            // true only if the converter rewrote the operator's file, and it does not. It emits a
            // fragment they merge, so a key it passes over never moves and cannot be dropped.
            // union, not boundPollKeys() alone. The two mapped keys are declared fields today, so
            // the union is currently redundant — but the guard must not depend on that. If those
            // dead fallbacks are ever removed from the model, this would start refusing
            // refresh-interval-ms, the one key the converter exists to migrate, while the extraction
            // two lines down still reads it by name. Structural beats asserted.
            final Set<String> accepted = new java.util.HashSet<>(boundPollKeys());
            accepted.addAll(POLL_KEYS);
            requireKnown(canonicalPoll.keySet(), accepted, "'riptide.snmp.poll'");
            refresh = wholeNumber(canonicalPoll.get("refreshintervalms"),
                    "riptide.snmp.poll.refresh-interval-ms");
            expiry = wholeNumber(canonicalPoll.get("snapshotexpiryms"),
                    "riptide.snmp.poll.snapshot-expiry-ms");
        }
        return new LegacyConfig(nodes, refresh, expiry);
    }

    private static LegacyNode node(final String name, final Object raw, final String sourceName) {
        final Map<String, Object> body = canonical(mapping(raw, "node '" + name + "'"), sourceName,
                "node '" + name + "'");
        requireKnown(body.keySet(), NODE_KEYS, "node '" + name + "'");

        final Object subnet = body.get("subnetaddress");
        if (subnet == null) {
            throw new IllegalStateException(
                    ("Node '%s' has no subnet-address. It matched nothing in 0.8 and has no 0.9 "
                            + "equivalent; remove it or give it an address.").formatted(name));
        }
        return new LegacyNode(name,
                String.valueOf(subnet),
                wholeNumber(body.get("observationdomain"), "node '" + name + "' observation-domain"),
                snmp(name, body.get("snmp"), sourceName),
                pins(name, body.get("interfaces"), sourceName));
    }

    private static LegacyNode.LegacySnmp snmp(final String node, final Object raw, final String sourceName) {
        if (raw == null) {
            return null;
        }
        final String where = "node '" + node + "' snmp block";
        final Map<String, Object> body = canonical(mapping(raw, where), sourceName, where);
        requireKnown(body.keySet(), SNMP_KEYS, where);

        final String version = text(body.get("snmpversion"), where + " snmp-version");
        if (version == null) {
            throw new IllegalStateException(
                    ("The %s has no snmp-version. 0.9 credential sets require one, and guessing it "
                            + "would guess how the community is sent.").formatted(where));
        }
        return new LegacyNode.LegacySnmp(version,
                text(body.get("community"), where + " community"),
                text(body.get("securityname"), where + " security-name"),
                text(body.get("authprotocol"), where + " auth-protocol"),
                text(body.get("authpassphrase"), where + " auth-passphrase"),
                text(body.get("privprotocol"), where + " priv-protocol"),
                text(body.get("privpassphrase"), where + " priv-passphrase"),
                boundedInt(body.get("timeout"), where + " timeout"),
                boundedInt(body.get("retries"), where + " retries"),
                boundedInt(body.get("port"), where + " port"));
    }

    private static Map<Integer, LegacyNode.LegacyPin> pins(final String node, final Object raw,
                                                           final String sourceName) {
        if (raw == null) {
            return Map.of();
        }
        final String where = "node '" + node + "' interfaces";
        final Map<Integer, LegacyNode.LegacyPin> pins = new TreeMap<>();
        for (final Map.Entry<String, Object> entry : mapping(raw, where).entrySet()) {
            final long parsed;
            try {
                parsed = Long.parseLong(entry.getKey().trim());
            } catch (final NumberFormatException e) {
                throw new IllegalStateException(
                        "The %s has a non-numeric ifIndex '%s'.".formatted(where, entry.getKey()), e);
            }
            if (parsed > Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "The %s has ifIndex %d, above the largest 0.9 accepts.".formatted(where, parsed));
            }
            final int ifIndex = (int) parsed;
            if (ifIndex <= 0) {
                throw new IllegalStateException(
                        ("The %s pins ifIndex %d. 0.9 rejects it: ifIndex is a positive integer and "
                                + "0 is the unknown-interface marker.").formatted(where, ifIndex));
            }
            final String pinWhere = where + " " + ifIndex;
            final Map<String, Object> body = canonical(mapping(entry.getValue(), pinWhere), sourceName, pinWhere);
            requireKnown(body.keySet(), PIN_KEYS, pinWhere);
            final Long highSpeed = wholeNumber(body.get("highspeed"), pinWhere + " high-speed");
            if (highSpeed != null && (highSpeed < 1 || highSpeed > 0xFFFF_FFFFL)) {
                throw new IllegalStateException(
                        ("The %s has high-speed %d. 0.9 bounds it to 1..4294967295 because it is a "
                                + "Gauge32 of Mbit/s that reaches utilization maths.")
                                .formatted(pinWhere, highSpeed));
            }
            // putIfAbsent, matching InventoryLoader: SnakeYAML sees 1 and "1" as different
            // keys, so its duplicate check misses the pair and one pin would silently vanish
            final LegacyNode.LegacyPin clash = pins.putIfAbsent(ifIndex, new LegacyNode.LegacyPin(
                    text(body.get("name"), pinWhere + " name"),
                    text(body.get("alias"), pinWhere + " alias"),
                    highSpeed));
            if (clash != null) {
                throw new IllegalStateException(
                        ("The %s declares ifIndex %d twice, in the quoted and unquoted spellings. "
                                + "YAML treats them as different keys, so one pin would be lost.")
                                .formatted(where, ifIndex));
            }
        }
        return pins;
    }

    /**
     * Folds kebab-case and camelCase onto one spelling, and refuses a body that uses both for
     * the same field.
     *
     * <p>Spring's relaxed binding made {@code subnet-address} and {@code subnetAddress} the
     * same property, so a config carrying both had one silently win. Which one won depended on
     * map order, so the operator's running configuration may not be the one they would read
     * off the file. Converting that quietly would carry the ambiguity forward into a tree that
     * is strict from birth.</p>
     */
    private static Map<String, Object> canonical(final Map<String, Object> body, final String sourceName,
                                                 final String where) {
        final Map<String, Object> folded = new LinkedHashMap<>();
        final Map<String, String> seen = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : body.entrySet()) {
            final String key = normalize(entry.getKey());
            final String previous = seen.put(key, entry.getKey());
            if (previous != null) {
                throw new IllegalStateException(
                        ("Legacy file %s: %s declares '%s' and '%s', which Spring bound to the same "
                                + "property. Which one applied depended on map order, so remove one "
                                + "and confirm the value is the one you intend.")
                                .formatted(sourceName, where, previous, entry.getKey()));
            }
            folded.put(key, entry.getValue());
        }
        return folded;
    }

    /** Spring's relaxed binding, reduced to what this converter needs: case and separators. */
    private static String normalize(final String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireKnown(final Set<String> present, final Set<String> known, final String where) {
        for (final String key : present) {
            if (!known.contains(key)) {
                throw new IllegalStateException(
                        ("The %s has a key '%s' the converter cannot map; known keys are %s. It is "
                                + "reported rather than dropped so nothing disappears from your "
                                + "configuration without you seeing it.")
                                .formatted(where, key, new TreeSet<>(known)));
            }
        }
    }

    private static Map<String, Object> parseYaml(final String content, final String sourceName) {
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setAllowDuplicateKeys(false);
        // loadAll, not load: a Spring application.yaml commonly carries profile documents
        // separated by '---', and load() rejects the whole file as invalid YAML, sending the
        // operator to hunt a syntax error that is not there. Documents are merged shallowly,
        // later ones winning, which is close enough to Spring's profile behaviour to convert
        // and is reported when it happens
        final java.util.List<Object> documents = new java.util.ArrayList<>();
        try {
            new Yaml(new SafeConstructor(options)).loadAll(content).forEach(documents::add);
        } catch (final YAMLException e) {
            throw new IllegalStateException(
                    "Legacy file %s is not valid YAML: %s".formatted(sourceName, e.getMessage()), e);
        }
        documents.removeIf(java.util.Objects::isNull);
        if (documents.isEmpty()) {
            throw new IllegalStateException("Legacy file %s is empty.".formatted(sourceName));
        }
        if (documents.size() > 1) {
            throw new IllegalStateException(
                    ("Legacy file %s contains %d YAML documents (Spring profile sections separated "
                            + "by '---'). Convert one profile at a time: split the file, or point the "
                            + "converter at the document whose riptide.nodes tree you want.")
                            .formatted(sourceName, documents.size()));
        }
        final Object loaded = documents.getFirst();
        if (!(loaded instanceof Map)) {
            // A .properties file lands here: 'a.b=c' is not YAML's key/value, so nothing maps.
            // Reporting only "no mapping at its root" reads as a corrupt file, when the file is
            // fine and the format is simply not the one this reads (#614).
            throw new IllegalStateException(
                    "Legacy file %s does not contain a mapping at its root. %s"
                            .formatted(sourceName, NESTED_YAML_HINT));
        }
        return stringKeyed(loaded, "the file root");
    }

    /**
     * The nodes mapping, with its keys held to being text.
     *
     * <p>A node key becomes an exporter name, so an unquoted {@code on:} or {@code 2024-01-01:}
     * would label flows "true" or with a host-timezone-dependent timestamp.</p>
     */
    private static Map<String, Object> nodeSection(final Map<String, Object> riptide) {
        for (final Map.Entry<String, Object> entry : riptide.entrySet()) {
            if (normalize(entry.getKey()).equals("nodes") && entry.getValue() instanceof Map) {
                return stringKeyed(entry.getValue(), "'riptide.nodes'", true);
            }
        }
        return null;
    }

    /** Looks up {@code key} in either spelling; {@code null} when absent or not a mapping. */
    private static Map<String, Object> child(final Map<String, Object> parent, final String key) {
        for (final Map.Entry<String, Object> entry : parent.entrySet()) {
            if (normalize(entry.getKey()).equals(normalize(key)) && entry.getValue() instanceof Map) {
                return stringKeyed(entry.getValue(), key);
            }
        }
        return null;
    }

    private static Map<String, Object> stringKeyed(final Object raw, final String where) {
        return stringKeyed(raw, where, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(final Object raw, final String where,
                                                   final boolean requireTextKeys) {
        final Map<String, Object> keyed = new LinkedHashMap<>();
        for (final Map.Entry<Object, Object> entry : ((Map<Object, Object>) raw).entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalStateException("A key under %s is null.".formatted(where));
            }
            if (requireTextKeys && !(entry.getKey() instanceof String)) {
                // an unquoted `on:` or `2024-01-01:` is a Boolean or a Date under YAML 1.1, and
                // stringifying it would name an exporter "true" or a host-timezone timestamp.
                // Only node names are held to this: an ifIndex key is a number by nature
                throw new IllegalStateException(
                        ("The key '%s' under %s is %s rather than text; quote it so it converts as "
                                + "the name you wrote.").formatted(entry.getKey(), where,
                                entry.getKey().getClass().getSimpleName()));
            }
            if (keyed.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue()) != null) {
                throw new IllegalStateException(
                        "Two keys under %s are both '%s'.".formatted(where, entry.getKey()));
            }
        }
        return keyed;
    }

    private static Map<String, Object> mapping(final Object raw, final String where) {
        if (!(raw instanceof Map)) {
            throw new IllegalStateException(
                    "The %s is not a mapping (found %s).".formatted(where,
                            raw == null ? "nothing" : raw.getClass().getSimpleName()));
        }
        return stringKeyed(raw, where);
    }

    /**
     * Text that was written as text. A bare {@code on} or a bare date is a Boolean or a Date to
     * SnakeYAML's YAML 1.1 resolver, and coercing it would put "true" or a host-timezone
     * timestamp into a credential or a pin. 2.7 found this on the 0.9 side; the legacy side can
     * carry the same shapes.
     */
    private static String text(final Object value, final String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException(
                    ("The %s is %s, which YAML read as %s rather than text. Quote it so it converts "
                            + "as the value you wrote.")
                            .formatted(where, value, value.getClass().getSimpleName()));
        }
        return text;
    }

    private static Long wholeNumber(final Object value, final String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) {
            throw new IllegalStateException(
                    "The %s is '%s', which is not a whole number.".formatted(where, value));
        }
        if (value instanceof java.math.BigInteger big && big.bitLength() > 63) {
            // longValue() truncates silently, so 2^64+1000 would convert to 1000
            throw new IllegalStateException(
                    "The %s is '%s', which is too large.".formatted(where, value));
        }
        return number.longValue();
    }

    private static Integer boundedInt(final Object value, final String where) {
        final Long number = wholeNumber(value, where);
        if (number == null) {
            return null;
        }
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalStateException("The %s is '%s', which is out of range.".formatted(where, number));
        }
        return number.intValue();
    }
}
