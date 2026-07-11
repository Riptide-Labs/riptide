/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;


import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "riptide.nodes[0].subnet-address=127.0.0.1/24",
        "riptide.nodes[0].snmp.port=12345",
        "riptide.nodes[0].snmp.snmp-version=v2c",
        "riptide.nodes[0].snmp.community=" + TestSnmpAgent.COMMUNITY
})
public class SnmpEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Autowired
    SnmpService snmpService;

    @Autowired
    NodeRegistry nodeRegistry;

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void testEnrichment(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.snmpService, this.nodeRegistry));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        pipeline.process(source, List.of(flow));

        snmpAgent.stop();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmp()).isEqualTo(1);
            assertThat(enrichedFlow.getOutputSnmp()).isEqualTo(2);
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("eth0-x");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("My ethernet interface");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(14L);
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0-x");
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isEqualTo("My loopback interface");
            assertThat(enrichedFlow.getOutputSnmpIfSpeed()).isEqualTo(34L);
        });
    }
}

