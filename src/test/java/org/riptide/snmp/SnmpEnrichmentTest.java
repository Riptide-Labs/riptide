package org.riptide.snmp;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.riptide.classification.ClassificationEngine;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.WithSource;
import org.riptide.repository.FlowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.codahale.metrics.MetricRegistry;

@SpringBootTest(properties = {
        "riptide.snmp.config.definitions[0].subnet-address.=127.0.0.1/24",
        "riptide.snmp.config.definitions[0].port=12345",
        "riptide.snmp.config.definitions[0].snmp-version=v2c",
        "riptide.snmp.config.definitions[0].community=" + TestSnmpAgent.COMMUNITY
})
public class SnmpEnrichmentTest {

    @Autowired
    Pipeline pipeline;

    @Autowired
    ClassificationEngine classificationEngine;

    @Autowired
    MetricRegistry metricRegistry;

    @Autowired
    SnmpCache snmpCache;

    @Autowired
    SnmpConfiguration snmpConfiguration;

    private int processedFlows;

    @Test
    public void testEnrichment(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        processedFlows = 0;

        final Map<String, FlowRepository> repositories = new TreeMap<>();
        repositories.put("my-flow-repository", flows -> {
            assertThat(flows).hasSizeGreaterThan(0);
            processedFlows += flows.size();
            for (final EnrichedFlow enrichedFlow : flows) {
                assertThat(enrichedFlow.getInputSnmp()).isEqualTo(1);
                assertThat(enrichedFlow.getOutputSnmp()).isEqualTo(2);
                assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("eth0-x");
                assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0-x");
            }
        });
        final Pipeline pipeline = new Pipeline(classificationEngine, repositories, metricRegistry, snmpConfiguration, snmpCache);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        final WithSource withSource = new WithSource<>("here", InetAddress.getByName("127.0.0.1"), Lists.list(flow));
        pipeline.process(withSource);

        snmpAgent.stop();

        assertThat(processedFlows).isEqualTo(1);
    }
}

