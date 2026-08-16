/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD-6's ordering is swap, then refresh, and until now it was asserted by a code comment:
 * deleting the refresh call, or moving it above the swap, left the whole suite green.
 */
@SpringBootTest(properties = "riptide.config.reload-interval=1h")
class ConfigReloadRefreshOrderingTest {

    /** Unique per run: a fixed shared path poisons the next run after a crash. */
    private static final Path CONFIG = createConfig();

    private static Path createConfig() {
        try {
            final Path file = Files.createTempDirectory("riptide-refresh-ordering").resolve("config.yaml");
            file.toFile().deleteOnExit();
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void configLocation(final org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "optional:file:" + CONFIG);
    }

    @Autowired
    private ConfigFileReloader reloader;

    @Autowired
    private Inventory inventory;

    /** Mocked so the call itself and its ordering against the swap are observable. */
    @MockitoBean
    private InterfaceSnapshotPoller interfacePoller;

    @BeforeEach
    void writeInitialConfig() throws IOException {
        Files.writeString(CONFIG, "riptide:\n  routing:\n    prefixes: {}\n");
        this.reloader.poll();
        Mockito.clearInvocations(this.interfacePoller);
    }

    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    @Test
    void aCommittedReloadRefreshesThePollerOnlyAfterPublishingTheSnapshot() throws Exception {
        final InventorySnapshot before = this.inventory.snapshot();
        // what is serving at the moment the refresh is called. The poller now reads the
        // inventory rather than being handed a snapshot, so the ordering has to be observed
        // where it matters instead of inferred from an argument
        final var servingWhenRefreshed = new java.util.concurrent.atomic.AtomicReference<InventorySnapshot>();
        Mockito.doAnswer(invocation -> {
            servingWhenRefreshed.set(this.inventory.snapshot());
            return null;
        }).when(this.interfacePoller).refreshRegistrations();

        Files.writeString(CONFIG, """
                riptide:
                  routing:
                    prefixes:
                      "[10.0.0.0/24]": { asn: 64512 }
                """);

        this.reloader.poll();

        Mockito.verify(this.interfacePoller).refreshRegistrations();
        // the reload really did republish, or "serving then" and "serving now" would be
        // the same object for the uninteresting reason
        assertThat(this.inventory.snapshot()).isNotSameAs(before);
        // and the refresh saw the new one: it cannot have run before the swap it follows
        assertThat(servingWhenRefreshed.get()).isSameAs(this.inventory.snapshot());
    }

    @Test
    void aRejectedReloadRefreshesNothing() throws Exception {
        Files.writeString(CONFIG, """
                riptide:
                  routing:
                    prefixes:
                      "[10.0.0.0/24]": { asn: 1 }
                      "[10.0.0.5/24]": { asn: 2 }
                """);

        this.reloader.poll();

        // the candidate never committed, so there is nothing for the poller to re-resolve
        Mockito.verifyNoInteractions(this.interfacePoller);
    }
}
