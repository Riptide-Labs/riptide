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

import java.util.Locale;
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

    /**
     * Compared against normalised property names, so every spelling that reaches Spring is
     * caught: {@code riptide.nodes.core-router...}, {@code riptide.nodes[0]...}, camelCase,
     * and the {@code RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS} environment form.
     *
     * <p>The env-var form is the one that matters most and is easiest to miss. A container
     * configured entirely through the environment is exactly the deployment most likely to be
     * carrying a legacy tree, and a check written against the dotted spelling alone would wave
     * it through.</p>
     */
    private static final String LEGACY_PREFIX = "riptidenodes";

    /**
     * The boundary that keeps the prefix honest. Stripping separators before a prefix test
     * makes {@code riptide.node.selector} normalise to {@code riptidenodeselector}, which
     * starts with {@code riptidenodes} and would hard-fail a deployment over a key that has
     * nothing to do with the legacy tree. {@code PollKeyMigrationCheck} avoided this by using
     * equality and says so; node names are unbounded here, so instead the raw name has to show
     * a real boundary after "nodes".
     */
    private static final java.util.regex.Pattern LEGACY_KEY = java.util.regex.Pattern.compile(
            "(?i)^riptide[._-]?nodes([._\\[]|$).*");

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
                    if (normalize(name).startsWith(LEGACY_PREFIX) && LEGACY_KEY.matcher(name).matches()) {
                        throw new IllegalStateException(message(name));
                    }
                    if (isEnvironmentForm(name)) {
                        throw new IllegalStateException(message(name));
                    }
                }
            }
        }
    }

    /**
     * The environment spelling, which has no separators left to anchor on:
     * {@code RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS}. Matched on the underscore boundary so
     * a Kubernetes service link for a service named {@code riptide-node}
     * ({@code RIPTIDE_NODE_SERVICE_HOST}) does not take the pod down.
     */
    private static boolean isEnvironmentForm(final String name) {
        return name.equals("RIPTIDE_NODES") || name.startsWith("RIPTIDE_NODES_");
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
                + "full upgrade guide.%s").formatted(key, indexedHint(key));
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

    /**
     * Spring's relaxed binding, reduced to what a prefix test needs: separators stripped and
     * lowercased, which folds the dotted, camelCase, indexed and environment spellings onto
     * one form. Matches {@code PollKeyMigrationCheck}.
     *
     * <p>A prefix rather than equality, because node names are unbounded. It stays narrow
     * enough not to touch the 0.9 surfaces: {@code riptide.inventory}, {@code riptide.snmp}
     * and {@code riptide.exporters} do not begin with these characters.</p>
     */
    private static String normalize(final String name) {
        return name.replace("-", "").replace("_", "").replace(".", "")
                .toLowerCase(Locale.ROOT);
    }
}
