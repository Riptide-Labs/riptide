/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.testsupport.LogCapture;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the inventory names its own source, which is one spelling and not three.
 *
 * <p>Two things depend on it. The boot sentence an operator with no inventory file reads, which is
 * the discovery-off path every existing operator is on. And {@link Inventory#documentName()}, which
 * {@code ConfigFileReloader}'s rebuild-failure WARNs now take their subject from, where they used
 * to build one from {@code riptide.inventory.file} and print {@code null} for a configuration that
 * discovery makes valid.</p>
 */
class InventoryDocumentNamingTest {

    private static final SnmpProfilesConfig PROFILES = new SnmpProfilesConfig(
            Map.of("corp", CredentialSet.community(CredentialVersion.V2C, SecretRef.of("public"))),
            Map.of());

    private static Inventory withNoFile() {
        return new Inventory(PROFILES, new FileInventoryDocument(new InventoryConfig()));
    }

    @Test
    void anUnsetInventoryFileIsNamedByItsKeyAndNeverAsNull() {
        assertThat(withNoFile().documentName())
                .isEqualTo("riptide.inventory.file (unset)")
                .doesNotContain("null");
    }

    /**
     * The boot line reads as one sentence rather than nesting a parenthetical inside a
     * parenthetical: the document's own name already carries "(unset)".
     */
    @Test
    void theEmptyInventoryBootLineNamesTheKeyWithoutNestingParentheses() {
        final Inventory inventory = withNoFile();
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(Inventory.class);
        final ListAppender<ILoggingEvent> captured = LogCapture.startedAppender();
        logger.addAppender(captured);
        try {
            inventory.load();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }

        assertThat(inventory.snapshot().agentCount()).isZero();
        assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Nothing to load from riptide.inventory.file (unset): serving the empty inventory"));
    }
}
