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

import static org.assertj.core.api.Assertions.assertThat;

public class ExporterNameEnricherTest {

    private static NodeRegistry registry() {
        final NodeDefinition node = new NodeDefinition();
        node.setSubnetAddress(new IPAddressString("192.168.10.1/32"));
        final NodeRegistry registry = new NodeRegistry();
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
}
