/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import jakarta.annotation.PostConstruct;
import org.riptide.utils.PropertyNames;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The bulk trees ({@code riptide.snmp.agents}, {@code riptide.exporters}) live only
 * in the direct-parsed file named by {@code riptide.inventory.file}; placed in the
 * main configuration they would bind to nothing and the relaxed binder would swallow
 * them without a diagnostic — a silently empty inventory. This check fails startup
 * loudly instead (the {@code NodesConfigMigrationCheck} precedent).
 */
@Component
public class InventoryMisplacementCheck {

    private static final Pattern MISPLACED_TREE = Pattern.compile(
            "^(riptide\\.(snmp\\.agents|exporters)[.\\[]|RIPTIDE_(SNMP_AGENTS|EXPORTERS)_)");

    private final Environment environment;

    public InventoryMisplacementCheck(final Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @PostConstruct
    void failOnMisplacedInventoryTrees() {
        if (!(this.environment instanceof AbstractEnvironment abstractEnvironment)) {
            return;
        }
        failOnMisplacedInventoryTrees(abstractEnvironment.getPropertySources());
    }

    /** Reusable against any source stack, matching the migration-check idiom. */
    public static void failOnMisplacedInventoryTrees(final Iterable<PropertySource<?>> sources) {
        findMisplacedInventoryKey(sources).ifPresent(name -> {
            throw new IllegalStateException(("Inventory tree found in the main configuration ('%s'): "
                    + "riptide.snmp.agents and riptide.exporters live only in the dedicated inventory "
                    + "file named by riptide.inventory.file — they bind to nothing here and would be "
                    + "silently ignored.").formatted(name));
        });
    }

    /** Non-throwing probe for the reloader's gated-document scan (#537); one probe per class, over the shared walk. */
    public static Optional<String> findMisplacedInventoryKey(final Iterable<PropertySource<?>> sources) {
        return PropertyNames.in(sources)
                .filter(name -> MISPLACED_TREE.matcher(name).find())
                .findFirst();
    }
}
