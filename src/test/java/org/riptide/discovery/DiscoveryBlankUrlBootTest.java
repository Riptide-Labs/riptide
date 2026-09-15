/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A blank {@code riptide.discovery.url} against the real object graph.
 *
 * <p>The defect this pins is a refusal to boot, not a wrong bean, and only a full context can show
 * that. {@code @ConditionalOnProperty} with no {@code havingValue} matched a present-but-empty
 * value, so {@code RIPTIDE_DISCOVERY_URL=""} — which a container image or a Helm template exports
 * whether or not it has a value — created the composed document, {@link Inventory} read it at boot,
 * and {@code DiscoveryConfig.endpoint()} threw "not a usable URL: ''". That is a content failure,
 * which the degraded-boot path deliberately does not catch, so the collector died at startup for an
 * operator who never asked for discovery.</p>
 *
 * <p>{@code DiscoveryWiringTest} pins the same gate more cheaply, but its collaborators include no
 * {@link Inventory} to read the document, so its context starts either way. This one has the bean
 * that used to throw.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryBlankUrlBootTest {

    private static final Path INVENTORY = writeInventory();

    @Autowired
    private Inventory inventory;

    @Autowired
    private InventoryDocument document;

    /** An agent range needing no credential set, so the test depends on no SNMP configuration. */
    private static Path writeInventory() {
        try {
            final Path file = Files.createTempFile("discovery-blank-url", ".yaml");
            file.toFile().deleteOnExit();
            Files.writeString(file, """
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            enabled: false
                    """);
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the inventory file", e);
        }
    }

    /** Whitespace rather than the empty string: blankness is the rule, not emptiness. */
    @DynamicPropertySource
    static void blankDiscoveryUrl(final DynamicPropertyRegistry registry) {
        registry.add("riptide.discovery.url", () -> "   ");
        registry.add("riptide.inventory.file", INVENTORY::toString);
    }

    @Test
    @Timeout(60)
    void aBlankUrlBootsTheCollectorWithDiscoveryOff() {
        // reaching the assertions at all is half of it: an unusable URL used to kill the refresh,
        // and a context that never starts fails this class before any assertion runs
        assertThat(this.document)
                .as("the file stays the only inventory document")
                .isInstanceOf(FileInventoryDocument.class);
        assertThat(this.inventory.snapshot().agentCount())
                .as("and the file's own trees are what is serving")
                .isEqualTo(1);
    }
}
