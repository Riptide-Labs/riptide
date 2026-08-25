/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import jakarta.annotation.PostConstruct;
import org.riptide.utils.PropertyNames;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fails startup on any surviving {@code riptide.nodes} configuration.
 *
 * <p>The 0.9 flag day. Enrichment, interface pins and SNMP credentials all come from the
 * inventory now, and the legacy tree is gone rather than inert: nothing binds it, so a
 * collector that started anyway would run with an operator's whole device configuration
 * silently doing nothing. The previous release warned instead, because the converter did not
 * exist yet and failing would have stranded anyone mid-migration. It exists now, and the
 * error names it.</p>
 *
 * <p>Absorbs the older indexed-list check. {@code riptide.nodes[0]} was its own migration
 * once; it is now one spelling of a tree that has no 0.9 equivalent at all, so a single rule
 * covers both and the operator gets the same instruction either way.</p>
 */
@Component
public class LegacyNodesFlagDayCheck {

    private static final java.util.regex.Pattern LEGACY_KEY = java.util.regex.Pattern.compile(
            "(?i)^riptide[._-]?nodes([._\\[\\-\\s]|$)");

    private final Environment environment;

    public LegacyNodesFlagDayCheck(final Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @PostConstruct
    void failOnLegacyNodes() {
        if (!(this.environment instanceof AbstractEnvironment abstractEnvironment)) {
            return;
        }
        failOnLegacyNodes(abstractEnvironment.getPropertySources());
    }

    /** Reusable against any source stack — the config hot reload runs it on candidates. */
    public static void failOnLegacyNodes(final Iterable<PropertySource<?>> sources) {
        findLegacyNodesKey(sources).ifPresent(name -> {
            throw new IllegalStateException(message(name));
        });
    }

    /** Non-throwing probe for the reloader's gated-document scan (#537); one probe per class, over the shared walk. */
    public static Optional<String> findLegacyNodesKey(final Iterable<PropertySource<?>> sources) {
        // lookingAt, not matches: matches() must consume the whole name, and '.'
        // excludes line terminators, so a key carrying a newline after "nodes" —
        // which a quoted YAML key or a .properties line can both produce — slipped
        // through the boundary check entirely and left the tree silently inert.
        // \s in the boundary class covers the terminator AT the boundary too.
        // The regex alone decides; a normalize-and-startsWith conjunct used to sit
        // here and was fully implied by it, two matching theories where one does
        return PropertyNames.in(sources)
                .filter(name -> LEGACY_KEY.matcher(name).lookingAt() && !isServiceLink(name))
                .findFirst();
    }

    /**
     * The fields a legacy node could actually carry, as environment-variable tails.
     *
     * <p>This is the disambiguator, and it is what the two previous attempts lacked. A name like
     * {@code RIPTIDE_NODES_EDGE_SERVICE_PORT_SUBNET_ADDRESS} is a service link for a Service named
     * {@code riptide-nodes-edge} with a port named {@code subnet-address}, <em>or</em> a node named
     * {@code edge-service-port} carrying a subnet address. Shape alone cannot tell them apart, so
     * both earlier versions guessed — and guessing toward "platform shape" is the silent direction,
     * which loses the operator's whole configuration with no signal.</p>
     *
     * <p>The legacy schema is bounded, so it can be matched positively instead of guessed at: a key
     * ending in a real node field is configuration, whatever platform shape it also resembles.</p>
     *
     * <p><b>Every alternative is a literal. No wildcard may span a separator here.</b> The first
     * draft of this pattern wrote the two nested groups as {@code SNMP(_[A-Z0-9_]+)?} — reproducing,
     * inside the fix, the exact defect it was fixing, because {@code [A-Z0-9_]+} spans {@code _} just
     * as {@code \w} does. That made {@code RIPTIDE_NODES_SNMP_SERVICE_HOST} a "node field", so a
     * Service named {@code riptide-nodes-snmp} crash-looped every pod — a shape that had been exempt
     * before the fix. Enumerate the leaves instead; the sets below are
     * {@code LegacyConfigReader}'s own {@code NODE_KEYS}, {@code SNMP_KEYS} and {@code PIN_KEYS}.</p>
     *
     * <p>Matched against the normalised name, which is what makes the relaxed-binding spellings fall
     * out for free: {@code subnetAddress}, {@code subnet_address} and {@code subnet-address} were all
     * legal in 0.8 and all normalise alike, where a raw-text pattern caught only one of them.</p>
     *
     * <p>The {@code .+} after {@code riptidenodes} is the node name, and it is required. Without it
     * {@code RIPTIDE_NODES_SNMP_PORT} reads as a node field, when no legacy key of that shape exists
     * — a node's SNMP port is {@code riptide.nodes.<name>.snmp.port}, which always carries a name.</p>
     */
    private static final Pattern LEGACY_FIELD_TAIL = Pattern.compile(
            "riptidenodes.+(subnetaddress|observationdomain"
                    + "|snmp(snmpversion|community|securityname|authprotocol|authpassphrase"
                    + "|privprotocol|privpassphrase|timeout|retries|port)"
                    + "|interfaces[0-9]+(name|alias|highspeed))$");

    /** Separator- and case-insensitive, matching how {@code LegacyConfigReader} compares its keys. */
    private static String normalised(final String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Container service links: environment variables the platform injects, which the operator
     * never wrote. A Kubernetes Service named {@code riptide-nodes} (or {@code riptide-nodes-
     * anything}) produces {@code ..._SERVICE_HOST} and friends; failing startup on those takes
     * a pod down over configuration that does not exist, with no in-namespace remedy but
     * renaming the Service.
     *
     * <p>Anchored at the end, not matched by prefix. The first version used unanchored
     * alternatives, which failed in both directions: a legacy node named {@code port-mirror}
     * produced {@code RIPTIDE_NODES_PORT_MIRROR_SUBNET_ADDRESS}, matched the {@code PORT}
     * alternative, and was waved through silently — while a Service named
     * {@code riptide-nodes-headless} was not exempted and crash-looped.
     *
     * <p><b>That second attempt was wrong in the same two directions</b>, which is why the
     * disambiguation now runs off {@link #LEGACY_FIELD_TAIL} rather off suffix shapes alone. It
     * claimed "no legacy node key can end in them, because node properties always continue with a
     * field name" — but {@code (_\w+)?} spans {@code _}, so the continuing field name was exactly
     * what the exemption absorbed; and it exempted only the bare {@code RIPTIDE_NODES_PORT} while
     * Kubernetes injects {@code {SVCNAME}_PORT} for every Service. Do not reduce this to suffix
     * matching again: the two readings of {@code RIPTIDE_NODES_EDGE_SERVICE_PORT_SUBNET_ADDRESS}
     * are genuinely indistinguishable by shape. Docker legacy-link
     * {@code _ENV_*} variables are deliberately not exempted: they are indistinguishable from
     * a node named {@code env-…}, and failing loudly on museum-grade Docker links beats
     * missing real configuration.</p>
     */
    private static boolean isServiceLink(final String name) {
        if (LEGACY_FIELD_TAIL.matcher(normalised(name)).matches()) {
            // a real node field beats every platform shape: loud beats silent, which is the same
            // call already made for Docker legacy-link _ENV_* variables
            return false;
        }
        return name.endsWith("_SERVICE_HOST")
                || name.matches(".*_SERVICE_PORT(_\\w+)?")
                || name.matches(".*_PORT_\\d+_(TCP|UDP)(_\\w+)?")
                // Kubernetes injects {SVCNAME}_PORT for EVERY Service, not just the unsuffixed
                // name, so a Service called riptide-nodes-headless yields RIPTIDE_NODES_HEADLESS_PORT
                // and the equality test above it crash-looped every pod in the namespace
                || name.matches("RIPTIDE_NODES(_[A-Z0-9]+)*_PORT")
                || name.equals("RIPTIDE_NODES_NAME");
    }

    private static String message(final String key) {
        return ("Legacy node configuration found ('%s'). riptide.nodes was removed in 0.9: exporter "
                + "names, interface pins and SNMP credentials now come from the credential sets and "
                + "polling profiles in the main config plus the inventory file "
                + "(riptide.inventory.file).%n%n"
                + "Convert it, do not delete it:%n"
                + "    riptide convert <your-config.yaml> --out-config config.yaml "
                + "--out-inventory inventory.yaml%n%n"
                + "The converter deduplicates credential blocks, keeps every exporter name, and "
                + "refuses rather than emitting anything 0.9 will not start on. Then remove the "
                + "riptide.nodes tree from your configuration. The 0.9 release notes carry the "
                + "full upgrade guide."
                // appended to the format string, not passed as an argument: a %n inside a %s
                // substitution is never processed and the operator reads a literal "%n%n"
                + indexedHint(key)).formatted(key);
    }

    /**
     * An indexed list cannot be converted: the converter reads riptide.nodes as a mapping and
     * an operator following the instruction above verbatim would be told their file has no
     * nodes. The old indexed-list check named the concrete fix, and AC 5 requires this one to
     * be at least as specific, so it keeps that instruction for this shape.
     */
    private static String indexedHint(final String key) {
        return key.contains("[")
                ? "%n%nThis is the indexed form. Rewrite it as a name-keyed map first "
                + "(riptide.nodes.<name>.subnet-address rather than riptide.nodes[0]...), which is "
                + "what the converter reads."
                : "";
    }

}
