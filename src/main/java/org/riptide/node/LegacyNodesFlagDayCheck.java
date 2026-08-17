/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Objects;

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
        for (final var source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    // lookingAt, not matches: matches() must consume the whole name, and '.'
                    // excludes line terminators, so a key carrying a newline after "nodes" —
                    // which a quoted YAML key or a .properties line can both produce — slipped
                    // through the boundary check entirely and left the tree silently inert.
                    // \s in the boundary class covers the terminator AT the boundary too.
                    // The regex alone decides; a normalize-and-startsWith conjunct used to sit
                    // here and was fully implied by it, two matching theories where one does
                    if (LEGACY_KEY.matcher(name).lookingAt() && !isServiceLink(name)) {
                        throw new IllegalStateException(message(name));
                    }
                }
            }
        }
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
     * {@code riptide-nodes-headless} was not exempted and crash-looped. The suffixes below are
     * exactly the shapes the platforms generate, and no legacy node key can end in them,
     * because node properties always continue with a field name. Docker legacy-link
     * {@code _ENV_*} variables are deliberately not exempted: they are indistinguishable from
     * a node named {@code env-…}, and failing loudly on museum-grade Docker links beats
     * missing real configuration.</p>
     */
    private static boolean isServiceLink(final String name) {
        return name.endsWith("_SERVICE_HOST")
                || name.matches(".*_SERVICE_PORT(_\\w+)?")
                || name.matches(".*_PORT_\\d+_(TCP|UDP)(_\\w+)?")
                || name.equals("RIPTIDE_NODES_PORT")
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
