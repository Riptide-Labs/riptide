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
import java.util.regex.Pattern;

/**
 * The pre-0.1.0 {@code riptide.nodes[<n>]} indexed list moved to the name-keyed
 * {@code riptide.nodes.<name>} map. Indexed keys would half-bind into a node named
 * after the index — this check fails startup loudly instead.
 */
@Component
public class NodesConfigMigrationCheck {

    private static final Pattern LEGACY_KEY = Pattern.compile("^riptide\\.nodes\\[\\d+]");

    private final Environment environment;

    public NodesConfigMigrationCheck(final Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @PostConstruct
    void failOnLegacyIndexedNodes() {
        if (!(this.environment instanceof AbstractEnvironment abstractEnvironment)) {
            return;
        }
        failOnLegacyIndexedNodes(abstractEnvironment.getPropertySources());
    }

    /** Reusable against any source stack — config hot-reload runs it on candidates. */
    public static void failOnLegacyIndexedNodes(final Iterable<PropertySource<?>> sources) {
        for (final var source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    if (LEGACY_KEY.matcher(name).find()) {
                        throw new IllegalStateException(("Legacy node configuration found ('%s'): riptide.nodes is a "
                                + "name-keyed map — configure riptide.nodes.<name>.subnet-address etc. instead of "
                                + "indexed riptide.nodes[0] entries (see the Nodes & SNMP documentation).")
                                .formatted(name));
                    }
                }
            }
        }
    }
}
