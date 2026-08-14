/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class ExporterNameEnricherTest {

    /** Counts lookups so tests can pin the at-most-once-per-batch contract (FR-3). */
    private static final class CountingRegistry extends NodeRegistry {
        private int lookups;

        @Override
        public Optional<Node> lookup(final ExporterIdentity identity) {
            this.lookups++;
            return super.lookup(identity);
        }
    }

    private static CountingRegistry registry() {
        final NodeDefinition node = new NodeDefinition();
        node.setSubnetAddress(new IPAddressString("192.168.10.1/32"));
        final CountingRegistry registry = new CountingRegistry();
        registry.setNodes(Map.of("bbone-fw01", node));
        registry.validate();
        return registry;
    }

    @Test
    void matchedNodeNameIsStamped() throws Exception {
        final var flow = EnrichedFlow.builder().build();
        final var source = new Source("default",
                new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.168.10.1"), 0));

        new ExporterNameEnricher(registry()).enrich(source, List.of(flow)).get();

        assertThat(flow.getExporterName()).isEqualTo("bbone-fw01");
    }

    @Test
    void unmatchedExporterStaysUnnamed() throws Exception {
        final var flow = EnrichedFlow.builder().build();
        final var source = new Source("default",
                new ExporterIdentity.NetflowIpfix(InetAddress.getByName("203.0.113.99"), 0));

        new ExporterNameEnricher(registry()).enrich(source, List.of(flow)).get();

        assertThat(flow.getExporterName()).isNull(); // persisted as '' via the ClickhouseFlow initializer
    }

    @Test
    void matchedBatchStampsEveryFlowWithExactlyOneLookup() throws Exception {
        final CountingRegistry registry = registry();
        final var flows = List.of(
                EnrichedFlow.builder().build(), EnrichedFlow.builder().build(), EnrichedFlow.builder().build());
        final var source = new Source("default",
                new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.168.10.1"), 0));

        new ExporterNameEnricher(registry).enrich(source, flows).get();

        assertThat(flows).allSatisfy(flow -> assertThat(flow.getExporterName()).isEqualTo("bbone-fw01"));
        // Source is constant across a batch, so the match is too (FR-3, AD-11); exactly
        // one also catches a regression that stops consulting the registry at all
        assertThat(registry.lookups).isEqualTo(1);
    }

    @Test
    void unmatchedBatchLeavesEveryFlowUnnamed() throws Exception {
        final CountingRegistry registry = registry();
        final var flows = List.of(EnrichedFlow.builder().build(), EnrichedFlow.builder().build());
        final var source = new Source("default",
                new ExporterIdentity.NetflowIpfix(InetAddress.getByName("203.0.113.99"), 0));

        new ExporterNameEnricher(registry).enrich(source, flows).get();

        assertThat(flows).allSatisfy(flow -> assertThat(flow.getExporterName()).isNull());
        assertThat(registry.lookups).isEqualTo(1);
    }
}
