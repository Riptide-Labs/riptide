/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.riptide.inventory.InventoryMisplacementCheck;
import org.riptide.inventory.PollKeyMigrationCheck;
import org.riptide.node.LegacyNodesFlagDayCheck;
import org.riptide.utils.PropertyNames;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Every configuration key this release does not read, reported in one failure.
 *
 * <p><strong>"Does not read", not "retired".</strong> Retired is accurate for some of these and
 * wrong for others: {@code riptide.snmp.agents} is a current key that belongs in the inventory file
 * rather than the main config, and {@code riptide.snmp.config.definitions} moved rather than
 * disappeared. The only property they share is that the operator wrote a key this release does not
 * read where it is written. Naming the set after the narrower property is not cosmetic —
 * {@code upgrading-from-0.8.md} generalised that way and omitted every category that was not
 * retired.</p>
 *
 * <p>Reporting them together is the point. Each check used to report its own first match and throw,
 * and the checks were separate beans with no ordering between them, so a configuration carrying six
 * offending keys produced six edit-and-restart cycles naming one key each, in an order the operator
 * could not predict. Under systemd or Kubernetes that is a restart loop, which the upgrade guide
 * says explicitly is not a log-review item (#562).</p>
 *
 * <p>Each category keeps its own remediation. They name different fixes — run the converter, move
 * cadence onto a polling profile, move a tree to the inventory file — and a merged generic message
 * would discard the only part worth reading. This class groups and bounds; it does not rewrite.</p>
 */
@Component
public class ObsoleteKeys {

    /**
     * Keys named per category before the list is summarised.
     *
     * <p>The same bound and the same reasoning as {@code InventoryLoader.MAX_NAMED_EMPTY_ENTRIES}:
     * a generated configuration can carry thousands, and naming every one would bury the rest of
     * startup. Mirrored rather than re-invented so operators meet one style of bounded diagnostic.
     */
    private static final int MAX_NAMED_KEYS = 20;

    private final Environment environment;

    public ObsoleteKeys(final Environment environment) {
        this.environment = java.util.Objects.requireNonNull(environment);
    }

    /**
     * One bean, one check.
     *
     * <p>This replaces three {@code @PostConstruct} hooks on three separate components. None of
     * them carried {@code @Order} or {@code @DependsOn}, so which category an operator was told
     * about was decided by bean creation order — unspecified, and different between two boots of the
     * same configuration. Collapsing them removes the race by construction rather than by ordering
     * them, which would have made the sequence predictable and left the restart loop intact.</p>
     */
    @PostConstruct
    void failOnObsoleteKeys() {
        if (!(this.environment instanceof AbstractEnvironment abstractEnvironment)) {
            return;
        }
        failOnObsoleteKeys(abstractEnvironment.getPropertySources());
    }

    /** One category: its label, how to find its keys, and how to remediate a group of them. */
    private record Category(String label,
                            Function<Iterable<PropertySource<?>>, List<PropertyNames.Located>> find,
                            Function<List<PropertyNames.Located>, String> remediation) {
    }

    private static final List<Category> CATEGORIES = List.of(
            new Category(LegacyNodesFlagDayCheck.LABEL,
                    sources -> LegacyNodesFlagDayCheck.matches(sources).toList(),
                    LegacyNodesFlagDayCheck::remediation),
            new Category(PollKeyMigrationCheck.LABEL,
                    sources -> PollKeyMigrationCheck.matches(sources).toList(),
                    found -> PollKeyMigrationCheck.remediation()),
            new Category(InventoryMisplacementCheck.LABEL,
                    sources -> InventoryMisplacementCheck.matches(sources).toList(),
                    found -> InventoryMisplacementCheck.remediation()));

    /**
     * Fails once, naming every key this release does not read, or returns quietly.
     *
     * <p>Reusable against any source stack: the boot check runs it over the environment, and the
     * config hot reload runs it over a candidate before installing it.</p>
     */
    public static void failOnObsoleteKeys(final Iterable<PropertySource<?>> sources) {
        final List<String> groups = new ArrayList<>();
        int total = 0;

        for (final Category category : CATEGORIES) {
            final List<PropertyNames.Located> found = category.find().apply(sources);
            if (found.isEmpty()) {
                continue;
            }
            total += found.size();
            groups.add(group(category, found));
        }

        if (groups.isEmpty()) {
            return;
        }
        throw new IllegalStateException(("Configuration carries %d key(s) this release does not "
                + "read:%n%n%s").formatted(total, String.join(System.lineSeparator()
                + System.lineSeparator(), groups)));
    }

    private static String group(final Category category, final List<PropertyNames.Located> found) {
        final List<String> named = found.stream()
                .map(PropertyNames.Located::name)
                .limit(MAX_NAMED_KEYS)
                .toList();
        final StringBuilder text = new StringBuilder()
                .append("  ").append(category.label()).append(" (").append(found.size()).append("): ")
                .append(String.join(", ", named));
        if (found.size() > MAX_NAMED_KEYS) {
            text.append(", and %d further key(s) listed no further"
                    .formatted(found.size() - MAX_NAMED_KEYS));
        }
        return text.append(System.lineSeparator())
                .append("    -> ")
                .append(category.remediation().apply(found))
                .toString();
    }
}
