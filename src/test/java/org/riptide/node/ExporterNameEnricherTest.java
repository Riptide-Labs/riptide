/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ExporterNameEnricherTest {

    /**
     * Counts view captures so tests can pin the at-most-once-per-batch contract (FR-3).
     * Counting on the {@code Inventory} rather than on the view is deliberate: capturing
     * per flow is the regression that matters, and it is invisible if you only count
     * matches.
     */
    private static final class CountingInventory extends Inventory {
        private final AtomicInteger captures = new AtomicInteger();

        private CountingInventory(final SnmpProfilesConfig profiles, final InventoryConfig config) {
            super(profiles, config);
        }

        @Override
        public InventorySnapshot snapshot() {
            this.captures.incrementAndGet();
            return super.snapshot();
        }
    }

    /**
     * A populated inventory, always. An empty one would make every "stays unnamed"
     * assertion below true for the wrong reason.
     */
    private static CountingInventory inventory() {
        final var profiles = new SnmpProfilesConfig(Map.of(), Map.of());
        final var inventory = new CountingInventory(profiles, new InventoryConfig());
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  exporters:
                    bbone-fw01:
                      address: 192.168.10.1
                """, "test.yaml"));
        return inventory;
    }

    private static Source source(final String address) throws Exception {
        return new Source("default", new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0));
    }

    @Test
    void matchedEntryNameIsStamped() throws Exception {
        final var flow = EnrichedFlow.builder().build();

        new ExporterNameEnricher(inventory()).enrich(source("192.168.10.1"), List.of(flow)).get();

        assertThat(flow.getExporterName()).isEqualTo("bbone-fw01");
    }

    @Test
    void unmatchedExporterStaysUnnamed() throws Exception {
        final var flow = EnrichedFlow.builder().build();

        // the inventory is populated, so this is a real miss rather than an empty tree
        new ExporterNameEnricher(inventory()).enrich(source("203.0.113.99"), List.of(flow)).get();

        assertThat(flow.getExporterName()).isNull(); // persisted as '' via the ClickhouseFlow initializer
    }

    @Test
    void aPrefixEntryNamesEveryDeviceItCovers() throws Exception {
        final var profiles = new SnmpProfilesConfig(Map.of(), Map.of());
        final var inventory = new CountingInventory(profiles, new InventoryConfig());
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  exporters:
                    access-switches:
                      address: 10.20.30.0/24
                """, "test.yaml"));
        final var flow = EnrichedFlow.builder().build();

        new ExporterNameEnricher(inventory).enrich(source("10.20.30.42"), List.of(flow)).get();

        // the label is site scoped by design: one entry, every device under it
        assertThat(flow.getExporterName()).isEqualTo("access-switches");
    }

    @Test
    void matchedBatchStampsEveryFlowWithExactlyOneCapture() throws Exception {
        final CountingInventory inventory = inventory();
        final var flows = List.of(
                EnrichedFlow.builder().build(), EnrichedFlow.builder().build(), EnrichedFlow.builder().build());

        new ExporterNameEnricher(inventory).enrich(source("192.168.10.1"), flows).get();

        assertThat(flows).allSatisfy(flow -> assertThat(flow.getExporterName()).isEqualTo("bbone-fw01"));
        // Source is constant across a batch, so the match is too (FR-3, AD-11). Exactly one
        // also catches a per-flow capture, which would let a reload split one batch across
        // two configuration generations
        assertThat(inventory.captures).hasValue(1);
    }

    @Test
    void unmatchedBatchLeavesEveryFlowUnnamedWithExactlyOneCapture() throws Exception {
        final CountingInventory inventory = inventory();
        final var flows = List.of(EnrichedFlow.builder().build(), EnrichedFlow.builder().build());

        new ExporterNameEnricher(inventory).enrich(source("203.0.113.99"), flows).get();

        assertThat(flows).allSatisfy(flow -> assertThat(flow.getExporterName()).isNull());
        assertThat(inventory.captures).hasValue(1);
    }
}
