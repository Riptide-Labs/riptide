/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;


import com.codahale.metrics.MetricRegistry;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
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
        // pinned in every field IfInfo carries, so the live rung can add nothing for it —
        // the case the liveness test below depends on
        "riptide.nodes.test-agent.interfaces.3.name=ge-0/0/3",
        "riptide.nodes.test-agent.interfaces.3.alias=Fully pinned by file",
        "riptide.nodes.test-agent.interfaces.3.high-speed=1000",
        "riptide.snmp.options.retentionMs=4242"
})
public class SnmpEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Autowired
    SnmpService snmpService;

    /**
     * These tests exercise the ladder's per-field merge authority, not polling. Resolving straight
     * from a live walk keeps them independent of the poller's schedule; the poller has its own
     * tests for registration, spreading and back-off.
     */
    private InterfaceSource liveSnmp() {
        return (endpoint, ifIndex) -> this.snmpService.getIfInfo(endpoint, ifIndex);
    }

    @Autowired
    NodeRegistry nodeRegistry;

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void testEnrichment(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), this.nodeRegistry, emptyInterfaceTable()));
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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), this.nodeRegistry, interfaceTable));
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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), this.nodeRegistry, interfaceTable));
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
    public void optionTableRetentionBindsFromProperties(@Autowired final SnmpOptionsConfig optionsConfig) {
        // regression: a bare public field never binds, and the option table would then run at a
        // 0 ms TTL — every exporter-pushed interface name expiring the instant it arrived
        assertThat(optionsConfig.getRetentionMs()).isEqualTo(4242);
    }

    @Test
    public void unknownIfIndexZeroSkipsTheWholeLadder() throws Exception {
        // single-direction exporters (e.g. pmacct nfprobe) emit ifIndex 0 on the untagged
        // side of every flow — that must not hit SNMP at all
        final InterfaceSource interfaceSource = Mockito.mock(InterfaceSource.class);

        final var enrichers = List.<Enricher>of(new SnmpEnricher(interfaceSource, this.nodeRegistry, emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(0);
        when(flow.getOutputSnmp()).thenReturn(0);

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        pipeline.process(source, List.of(flow));

        Mockito.verifyNoInteractions(interfaceSource);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isNull();
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isNull();
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isNull();
            assertThat(enrichedFlow.getOutputSnmpIfName()).isNull();
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isNull();
            assertThat(enrichedFlow.getOutputSnmpIfSpeed()).isNull();
        });
    }

    @Test
    public void pinnedInterfacesStillCallTrackAndResolveToMaintainLiveness() throws Exception {
        // ifIndex 3 is pinned in name, alias and highSpeed, so the live rung cannot contribute a
        // single field for it and skipping the call would look free. It is not: the call is what
        // registers the exporter and refreshes its liveness, so withholding it would deregister
        // the exporter and cost ifIndex 2 — pinned in nothing — its alias and speed for a whole
        // walk cycle. Guards the short-circuit proposed in #446.
        final InterfaceSource interfaceSource = Mockito.mock(InterfaceSource.class);
        when(interfaceSource.trackAndResolve(Mockito.any(), Mockito.anyInt())).thenReturn(java.util.Optional.empty());

        final var enrichers = List.<Enricher>of(new SnmpEnricher(interfaceSource, this.nodeRegistry, emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(3);
        when(flow.getOutputSnmp()).thenReturn(2);

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));
        pipeline.process(source, List.of(flow));

        final var targetIp = InetAddress.getByName("127.0.0.1");
        // the fully pinned interface: the assertion that actually fails if the ladder short-circuits
        Mockito.verify(interfaceSource).trackAndResolve(Mockito.argThat(ep -> ep != null && ep.getInetSocketAddress().getAddress().equals(targetIp)), Mockito.eq(3));
        Mockito.verify(interfaceSource).trackAndResolve(Mockito.argThat(ep -> ep != null && ep.getInetSocketAddress().getAddress().equals(targetIp)), Mockito.eq(2));

        // and the pin still reaches the flow, so the mock's empty live rung proves the pin
        // supplied every field rather than the enricher silently dropping them
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("ge-0/0/3");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Fully pinned by file");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(1000L);
        });
    }

    private static ExporterInterfaceTable emptyInterfaceTable() {
        final SnmpOptionsConfig cacheConfig = new SnmpOptionsConfig();
        cacheConfig.setRetentionMs(60_000);
        return new ExporterInterfaceTable(cacheConfig, new SessionAdmissionConfig(), new MetricRegistry());
    }

    /**
     * An interface whose option entry was evicted by the per-scope cap must still be enriched by the
     * rungs above and below it, and its flow must still be emitted.
     *
     * <p>This is what makes the cap safe to set: it costs the option rung for that interface and
     * nothing else. A cap that silently dropped flows, or blanked interfaces a static pin already
     * covered, would trade a memory-exhaustion bug for a data-loss one.
     */
    @Test
    public void interfaceEvictedByTheScopeCapStillEnrichesFromPinsAndSnmp(@TempDir Path temporaryFolder)
            throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        final SnmpOptionsConfig cacheConfig = new SnmpOptionsConfig();
        cacheConfig.setRetentionMs(60_000);
        final SessionAdmissionConfig capped = new SessionAdmissionConfig();
        capped.setMaxIfIndexesPerScope(1);
        final var metrics = new MetricRegistry();
        final ExporterInterfaceTable interfaceTable =
                new ExporterInterfaceTable(cacheConfig, capped, metrics);

        // Interface 1 is pushed first, then a second interface displaces it: with a budget of one,
        // interface 1's option entry is gone by the time the flow referencing it arrives.
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "opt-if1"), new StringValue("IF_DESC", "opt-desc1")));
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 2)),
                List.of(new StringValue("IF_NAME", "opt-if2"), new StringValue("IF_DESC", "opt-desc2")));

        assertThat(metrics.meter("enrichment.optionInterfaces.rejected").getCount())
                .as("the eviction is counted, not silent")
                .isEqualTo(1);

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), this.nodeRegistry, interfaceTable));
        final var repository = new TestRepository(this.metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        pipeline.process(source, List.of(flow));

        snmpAgent.stop();

        assertThat(repository.flows())
                .as("the flow is still emitted — eviction degrades enrichment, it does not deny the flow")
                .isNotEmpty()
                .allSatisfy(enrichedFlow -> {
                    // interface 1 lost its option entry, so the live SNMP name is what remains
                    assertThat(enrichedFlow.getInputSnmpIfName())
                            .as("live SNMP still resolves the evicted interface")
                            .isNotNull()
                            .isNotEqualTo("opt-if1");
                    // and the static pin above the option rung is untouched by the eviction
                    assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Uplink pinned by file");
                    assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(14L);
                    // the surviving entry still wins its field, so the cap cost exactly one interface
                    assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("opt-if2");
                });
    }
}
