/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.flows.parser.data.Flow;
import org.riptide.node.NodeRegistry;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.riptide.repository.TestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The enrichment ladder's middle rung on its own: a node with a static interface map
 * and no SNMP block enriches without any reachable agent.
 */
@SpringBootTest(properties = {
        "riptide.nodes.static-only.subnet-address=127.0.0.1/24",
        "riptide.nodes.static-only.interfaces.1.name=eth0",
        "riptide.nodes.static-only.interfaces.1.alias=Uplink to AS64500",
        "riptide.nodes.static-only.interfaces.1.high-speed=10000",
        "riptide.nodes.static-only.interfaces.2.name=lo0"
})
public class StaticInterfaceEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Autowired
    SnmpService snmpService;

    @Autowired
    NodeRegistry nodeRegistry;

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void staticMappingEnrichesWithoutSnmp() throws Exception {
        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.snmpService, this.nodeRegistry));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        pipeline.process(new Source("here", InetAddress.getByName("127.0.0.1")), List.of(flow));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("eth0");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Uplink to AS64500");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(10000L);
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0");
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isNull();
        });
    }
}
