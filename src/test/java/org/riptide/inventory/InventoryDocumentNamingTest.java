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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The file document's three spellings, pinned as literals.
     *
     * <p>They are what every message on the discovery-off path renders, which is the path every
     * existing operator is on, so this test is the byte-identity check for #803: the noun moved to
     * the seam, and moving it must not have reworded anything here.</p>
     */
    @Test
    void theFileDocumentSpellsItTheWayEveryMessageAlreadyDid() {
        final InventoryDocument file = new FileInventoryDocument(new InventoryConfig());

        assertThat(file.subject()).isEqualTo("Inventory file riptide.inventory.file (unset)");
        assertThat(file.noun()).isEqualTo("inventory file");
        assertThat(file.partialReadAdvice())
                .isEqualTo("a partially written file reads this way; write atomically via mv");
    }

    /**
     * A document that is not a file changes the sentences that name it, with no caller branching on
     * whether discovery is enabled. The regression refusal is the one checked here because it is the
     * only one of the four with no coverage of its own, and because its advice used to prescribe an
     * mv for a condition an endpoint's short response can produce just as easily.
     */
    @Test
    void aDocumentThatIsNotAFileChangesTheRefusalThatNamesIt() {
        final Inventory inventory = new Inventory(PROFILES, new NotAFile());
        inventory.swap(InventoryLoader.parse(PROFILES, """
                riptide:
                  snmp:
                    agents:
                      10.0.0.1:
                        credentials: corp
                """, "probe"));

        assertThatThrownBy(() -> inventory.swap(InventoryLoader.parse(PROFILES, "riptide:\n", "probe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the next poll composes it again")
                .hasMessageNotContaining("write atomically via mv")
                .hasMessageNotContaining("partially written file");
    }

    /**
     * A loader failure names the document's own subject. The loader has no document and no way to
     * know whether an endpoint contributed, so before #803 it prefixed "Inventory file" to whatever
     * name it was handed, and a malformed composed document was reported as a malformed file.
     */
    @Test
    void aLoaderFailureNamesTheDocumentRatherThanAssumingAFile() {
        final Inventory inventory = new Inventory(PROFILES, new NotAFile("riptide:\n  snmp: [nope]\n"));

        assertThatThrownBy(inventory::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not a file a file + an endpoint")
                .hasMessageNotContaining("Inventory file");
    }

    /** A document whose source is not a file, standing in for the composed one. */
    private record NotAFile(String text) implements InventoryDocument {
        private NotAFile() {
            this(null);
        }

        @Override
        public String subject() {
            return "Not a file " + name();
        }

        @Override
        public String name() {
            return "a file + an endpoint";
        }

        @Override
        public String partialReadAdvice() {
            return "a short read of either half reads this way; the next poll composes it again";
        }
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
