/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.Inventory;
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
 * Discovery enabled, its watcher disabled: {@code riptide.discovery.interval} at zero.
 *
 * <p>Enabled and running are not the same thing, and this configuration is the one where a boot
 * that could not reach the endpoint heals only through a credential rotation or a restart. The
 * documentation makes three promises about it and, until #808, nothing tested any of them. The
 * boot warning's wording is covered next to the clause that produces it, in
 * {@code ComposedInventoryDocumentTest}; what needs a context is which metrics exist.</p>
 *
 * <p><b>The gauge assertion is the load-bearing one.</b> A staleness gauge registered here would
 * read a constant 0, which an operator reads as "the document matches what is serving" about a
 * document that is never read again. That is worse than no gauge: it answers a question it cannot
 * answer. The counters are the opposite case and are asserted present, because zero really is the
 * truth for a reload that never runs, and an absent counter cannot be told apart from a collector
 * that has simply not reloaded yet.</p>
 *
 * <p>Its own context, because the properties under test are how the context boots. The endpoint is
 * a port nothing can be listening on, so boot degrades exactly as {@code DiscoveryBootDegradedTest}
 * covers, with no HTTP server to run. The positive-interval twin of the gauge assertion lives
 * there: it reads {@code inventory.reload.stale} and fails if it is missing.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryWatcherDisabledTest {

    /** Nothing can listen here without root, so every fetch is a refused connection. */
    private static final String ENDPOINT = "http://127.0.0.1:1/devices";

    private static final Path INVENTORY = writeInventory();

    @Autowired
    private Inventory inventory;

    @Autowired
    private MetricRegistry metrics;

    /** An agent range needing no credential set, so the test depends on no SNMP configuration. */
    private static Path writeInventory() {
        try {
            final Path file = Files.createTempFile("discovery-watcher-disabled", ".yaml");
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

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("riptide.discovery.url", () -> ENDPOINT);
        registry.add("riptide.discovery.interval", () -> "0s");
        registry.add("riptide.inventory.file", INVENTORY::toString);
    }

    /**
     * The name says only what this method asserts. Nothing here observes the schedule: that nothing
     * is scheduled is proven by {@link #noGaugeIsRegisteredThatCouldOnlyReportAConstant}, because a
     * watcher that started would have registered its gauges from {@code FileWatchTrigger.start}.
     */
    @Test
    void theCollectorBootsDegradedWithTheEndpointUnreachable() {
        assertThat(this.inventory.snapshot().agentCount())
                .as("the context started and serves the file's agent ranges")
                .isEqualTo(1);
        assertThat(this.inventory.snapshot().exporterCount())
                .as("with no exporters, because the endpoint could not be reached")
                .isZero();
    }

    @Test
    void noGaugeIsRegisteredThatCouldOnlyReportAConstant() {
        assertThat(this.metrics.getGauges().keySet())
                .as("a staleness gauge here would read 0 forever about a document never read again")
                .doesNotContain("inventory.reload.stale", "inventory.reload.dead");
    }

    @Test
    void theReloadCountersStayRegisteredBecauseZeroIsTrueForThem() {
        assertThat(this.metrics.getCounters().keySet())
                .as("created in the constructor deliberately; this pins the reasoning that a later "
                        + "tidy-up moving them into start() for symmetry would otherwise break silently")
                .contains("inventory.reload.successes", "inventory.reload.failures");
        assertThat(this.metrics.counter("inventory.reload.successes").getCount()).isZero();
        assertThat(this.metrics.counter("inventory.reload.failures").getCount()).isZero();
    }
}
