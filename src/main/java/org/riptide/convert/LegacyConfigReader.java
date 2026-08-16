/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 */
public final class LegacyConfigReader {

    private static final Set<String> NODE_KEYS =
            Set.of("subnetaddress", "observationdomain", "snmp", "interfaces");

    private static final Set<String> SNMP_KEYS =
            Set.of("snmpversion", "community", "securityname", "authprotocol", "authpassphrase",
                    "privprotocol", "privpassphrase", "timeout", "retries", "port");

    private static final Set<String> PIN_KEYS = Set.of("name", "alias", "highspeed");

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
            throw new IllegalStateException(
                    "Legacy file %s has no 'riptide' section: is this a riptide configuration?"
                            .formatted(sourceName));
        }
        final Map<String, LegacyNode> nodes = new LinkedHashMap<>();
        final Map<String, Object> rawNodes = child(riptide, "nodes");
        if (rawNodes != null) {
            for (final Map.Entry<String, Object> entry : rawNodes.entrySet()) {
                nodes.put(entry.getKey(), node(entry.getKey(), entry.getValue(), sourceName));
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    ("Legacy file %s declares no nodes under 'riptide.nodes': there is nothing to "
                            + "convert. If the nodes live in another file, convert that one.")
                            .formatted(sourceName));
        }

        final Map<String, Object> poll = riptide.containsKey("snmp")
                ? child(canonical(section(riptide, "snmp", sourceName), sourceName, "riptide.snmp"), "poll")
                : null;
        Long refresh = null;
        Long expiry = null;
        if (poll != null) {
            final Map<String, Object> canonicalPoll = canonical(poll, sourceName, "riptide.snmp.poll");
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
            final int ifIndex;
            try {
                ifIndex = Integer.parseInt(entry.getKey().trim());
            } catch (final NumberFormatException e) {
                throw new IllegalStateException(
                        "The %s has a non-numeric ifIndex '%s'.".formatted(where, entry.getKey()), e);
            }
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
            pins.put(ifIndex, new LegacyNode.LegacyPin(
                    text(body.get("name"), pinWhere + " name"),
                    text(body.get("alias"), pinWhere + " alias"),
                    highSpeed));
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
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(content);
        } catch (final YAMLException e) {
            throw new IllegalStateException(
                    "Legacy file %s is not valid YAML: %s".formatted(sourceName, e.getMessage()), e);
        }
        if (loaded == null) {
            throw new IllegalStateException("Legacy file %s is empty.".formatted(sourceName));
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalStateException(
                    "Legacy file %s does not contain a mapping at its root.".formatted(sourceName));
        }
        return stringKeyed(loaded, "the file root");
    }

    private static Map<String, Object> section(final Map<String, Object> parent, final String key,
                                               final String sourceName) {
        final Map<String, Object> found = child(parent, key);
        if (found == null) {
            throw new IllegalStateException(
                    "Legacy file %s: '%s' is not a mapping.".formatted(sourceName, key));
        }
        return found;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(final Object raw, final String where) {
        final Map<String, Object> keyed = new LinkedHashMap<>();
        for (final Map.Entry<Object, Object> entry : ((Map<Object, Object>) raw).entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalStateException("A key under %s is null.".formatted(where));
            }
            keyed.put(String.valueOf(entry.getKey()), entry.getValue());
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
        if (!(value instanceof String)) {
            throw new IllegalStateException(
                    ("The %s is %s, which YAML read as %s rather than text. Quote it so it converts "
                            + "as the value you wrote.")
                            .formatted(where, value, value.getClass().getSimpleName()));
        }
        return (String) value;
    }

    private static Long wholeNumber(final Object value, final String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float) {
            throw new IllegalStateException(
                    "The %s is '%s', which is not a whole number.".formatted(where, value));
        }
        return ((Number) value).longValue();
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
