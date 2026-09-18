/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.junit.jupiter.api.Test;
import org.riptide.classification.internal.ClassificationRulesSource;
import org.riptide.discovery.ComposedInventoryDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sources can satisfy the inventory watcher's dependency, and which cannot.
 *
 * <p>The pair reads as one statement. {@code InventoryFileReloader} is injected by
 * {@link PacedInventorySource} rather than by {@link FileWatchTrigger.Source} because more than one
 * component implements the wider interface, and resolving to the wrong one is silent: the watcher
 * would poll the classification ruleset, the inventory would never be re-read, and nothing would
 * report an error.</p>
 *
 * <p>Asserted rather than commented, because the property is one a future change can remove without
 * noticing. Giving {@code ClassificationRulesSource} an {@code interval()} for unrelated reasons
 * would not make it implement this interface, but the day someone declares the interface on it, this
 * test is what says why that is not a free change (#806).</p>
 *
 * <p><b>What this does not cover.</b> These are assignability checks, not injection: nothing here
 * starts a container, so a second {@link PacedInventorySource} bean appearing somewhere would make
 * startup ambiguous without failing this class. What covers that is every {@code @SpringBootTest}
 * that boots with discovery on — {@code DiscoveryReloadTest} and {@code DiscoveryBootDegradedTest}
 * both wire the real watcher against the real context, and an unresolvable or ambiguous dependency
 * fails them at startup.</p>
 */
class InventoryWatcherInjectionTest {

    @Test
    void theClassificationRulesetCannotSatisfyTheInventoryWatcher() {
        assertThat(PacedInventorySource.class.isAssignableFrom(ClassificationRulesSource.class))
                .as("the watcher's type must not be satisfiable by the other Source bean")
                .isFalse();
        assertThat(FileWatchTrigger.Source.class.isAssignableFrom(ClassificationRulesSource.class))
                .as("while the wider interface is, which is exactly why the watcher does not use it")
                .isTrue();
    }

    @Test
    void theComposedDiscoveryDocumentCan() {
        assertThat(PacedInventorySource.class.isAssignableFrom(ComposedInventoryDocument.class)).isTrue();
    }
}
