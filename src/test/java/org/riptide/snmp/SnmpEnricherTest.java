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
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
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
        "riptide.nodes.test-agent.subnet-address=127.0.0.1/24",
        "riptide.nodes.test-agent.snmp.port=12345",
        "riptide.nodes.test-agent.snmp.snmp-version=v2c",
        "riptide.nodes.test-agent.snmp.community=" + TestSnmpAgent.COMMUNITY,
        // enrichment-ladder per-field pin: static alias overrides SNMP, rest is live
        "riptide.nodes.test-agent.interfaces.1.alias=Uplink pinned by file",
        "riptide.snmp.cache.retentionMs=4242"
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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.snmpService, this.nodeRegistry, emptyInterfaceTable()));
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
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Uplink pinned by file");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(14L);
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0-x");
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isEqualTo("My loopback interface");
            assertThat(enrichedFlow.getOutputSnmpIfSpeed()).isEqualTo(34L);
        });
    }


    @Test
    public void optionDataJoinsTheLadderWithPerFieldAuthority(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        // exporter pushed option records for both interfaces (name + description)
        final ExporterInterfaceTable interfaceTable = emptyInterfaceTable();
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "opt-if1"),
                        new StringValue("IF_DESC", "opt-desc1")));
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 2)),
                List.of(new StringValue("IF_NAME", "opt-if2"),
                        new StringValue("IF_DESC", "opt-desc2")));

        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.snmpService, this.nodeRegistry, interfaceTable));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        pipeline.process(source, List.of(flow));

        snmpAgent.stop();

        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            // name: options beat live SNMP (eth0-x / lo0-x)
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("opt-if1");
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("opt-if2");
            // alias: static pin first, then SNMP ifAlias beats the option description
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Uplink pinned by file");
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isEqualTo("My loopback interface");
            // speed: SNMP only
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(14L);
            assertThat(enrichedFlow.getOutputSnmpIfSpeed()).isEqualTo(34L);
        });
    }


    @Test
    public void optionDataEnrichesWithoutAnyConfiguredNode() throws Exception {
        // 10.99.0.1 matches no riptide.nodes entry — the zero-config rung
        final var source = new Source("here", InetAddress.getByName("10.99.0.1"));

        final ExporterInterfaceTable interfaceTable = emptyInterfaceTable();
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "no-node-if1"), new StringValue("IF_DESC", "pushed")));

        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.snmpService, this.nodeRegistry, interfaceTable));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);

        pipeline.process(source, List.of(flow));

        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("no-node-if1");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("pushed");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isNull();
        });
    }

    @Test
    public void cacheRetentionBindsFromProperties(@Autowired final SnmpCacheConfig cacheConfig) {
        // regression: a bare public field never binds — both caches then run at 0 ms TTL
        assertThat(cacheConfig.getRetentionMs()).isEqualTo(4242);
    }

    private static ExporterInterfaceTable emptyInterfaceTable() {
        final SnmpCacheConfig cacheConfig = new SnmpCacheConfig();
        cacheConfig.setRetentionMs(60_000);
        return new ExporterInterfaceTable(cacheConfig, new MetricRegistry());
    }
}
