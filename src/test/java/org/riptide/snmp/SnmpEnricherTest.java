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
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "riptide.snmp.options.retentionMs=4242")
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

    /**
     * The inventory these tests run against, always populated. An empty one would make
     * every "never polled" and "stays unnamed" assertion below true for the wrong
     * reason, which is the vacuity trap the pre-cutover audit flagged for this cutover.
     *
     * <p>The agent range is the loopback host the test agent binds, on its port; the
     * enrichment entry carries the same per-field pins the legacy properties used to:
     * ifIndex 1 pins only an alias so the live rung fills the rest, and ifIndex 3 pins
     * every field IfInfo carries, which is what the liveness test depends on.</p>
     */
    private Inventory inventory() {
        final var profiles = new SnmpProfilesConfig(
                Map.of("agent-v2c", CredentialSet.community(CredentialVersion.V2C,
                        SecretRef.of(TestSnmpAgent.COMMUNITY))),
                Map.of());
        final var inventory = new Inventory(profiles, new InventoryConfig());
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "127.0.0.1":
                        credentials: agent-v2c
                        port: 12345
                  exporters:
                    test-agent:
                      address: 127.0.0.1
                      interfaces:
                        1: { alias: "Uplink pinned by file" }
                        3: { name: "ge-0/0/3", alias: "Fully pinned by file", high-speed: 1000 }
                """, "test.yaml"));
        return inventory;
    }

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void testEnrichment(@TempDir Path temporaryFolder) throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), inventory(), emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        final var source = new Source("here", InetAddress.getLoopbackAddress());

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

        final var source = new Source("here", InetAddress.getLoopbackAddress());

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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), inventory(), interfaceTable));
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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), inventory(), interfaceTable));
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
    public void unmatchedExporterIsCollectedAndOptionEnrichedButNeverPolled() throws Exception {
        // FR-8: an address covered by no agent range is collected and option-data-enriched,
        // is never polled, and creates no registration. Pinned on the path that is live
        // today (nodes); story 2.8 cuts the same behaviour over to the inventory views and
        // must keep this green. Registration is a side effect of the only method on
        // InterfaceSource, so "no interaction" is exactly "no registration".
        final var source = new Source("here", InetAddress.getByName("10.99.0.1"));
        final InterfaceSource interfaceSource = Mockito.mock(InterfaceSource.class);

        final ExporterInterfaceTable interfaceTable = emptyInterfaceTable();
        interfaceTable.accept(source.identity(),
                List.of(new UnsignedValue("SCOPE:INTERFACE", 1)),
                List.of(new StringValue("IF_NAME", "no-node-if1"), new StringValue("IF_DESC", "pushed")));

        final var enrichers = List.<Enricher>of(new SnmpEnricher(interfaceSource, inventory(), interfaceTable));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        // non-zero: ifIndex 0 short-circuits the ladder, which would pass for the wrong reason
        when(flow.getInputSnmp()).thenReturn(1);

        pipeline.process(source, List.of(flow));

        Mockito.verifyNoInteractions(interfaceSource);
        assertThat(repository.flows()).isNotEmpty().allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("no-node-if1");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("pushed");
            // the SNMP-only field stays empty: nothing was ever walked
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isNull();
        });
    }

    /**
     * UJ-1, the milestone's headline promise: a device inside a credentialed range, named
     * in no configuration tree of its own, ends up with SNMP-derived interface names on
     * its flows. Zero-touch is the whole claim, so the range has to be wider than one host
     * and the device must not be enumerated anywhere, which is why this needs v3: a range
     * wider than a host may not carry a cleartext community.
     *
     * <p>At the integration tier rather than the e2e on purpose. The SNMP e2e is gated
     * behind an environment variable and a host route, so it runs on nobody's build; this
     * runs on every one.</p>
     */
    @Test
    public void aDeviceInACredentialedRangeIsEnrichedWithoutBeingConfigured(@TempDir Path temporaryFolder)
            throws Exception {
        final TestSnmpAgent snmpAgent = new TestSnmpAgent("127.0.0.1/12345", temporaryFolder);
        snmpAgent.start();
        snmpAgent.registerIfTable();
        snmpAgent.registerIfXTable();

        // a v3 credential set and one wide range; 127.0.0.1 appears nowhere else, and there
        // is no enrichment entry for it at all, so nothing here names the device
        final var profiles = new SnmpProfilesConfig(
                Map.of("corp-v3", new CredentialSet(CredentialVersion.V3, null,
                        TestSnmpAgent.AUTHNOPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1,
                        SecretRef.of(TestSnmpAgent.AUTHNOPRIV_AUTH_PASSHRASE), null, null)),
                Map.of());
        final var zeroTouch = new Inventory(profiles, new InventoryConfig());
        zeroTouch.swap(InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "127.0.0.0/24":
                        credentials: corp-v3
                        port: 12345
                """, "zero-touch.yaml"));

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), zeroTouch, emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        pipeline.process(new Source("here", InetAddress.getLoopbackAddress()), List.of(flow));
        snmpAgent.stop();

        assertThat(repository.flows()).isNotEmpty().allSatisfy(enrichedFlow -> {
            // walked, not pinned: nothing in the configuration carries these values
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("eth0-x");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("My ethernet interface");
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0-x");
        });
    }

    @Test
    public void aBatchCapturesTheInventoryExactlyOnce() throws Exception {
        // the regression that matters is a per-flow capture: it compiles, reads correctly,
        // and lets a reload land mid-batch so some flows are enriched from one generation
        // and the rest from another. Counting matches would not see it; counting captures
        // does, which is why the double counts snapshot() rather than view lookups
        final var counting = new CountingInventory(inventory());
        final var enrichers = List.<Enricher>of(
                new SnmpEnricher(liveSnmp(), counting, emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final var flows = new java.util.ArrayList<Flow>();
        for (int i = 0; i < 25; i++) {
            final Flow flow = Mockito.mock(Flow.class);
            when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
            when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
            when(flow.getInputSnmp()).thenReturn(1);
            when(flow.getOutputSnmp()).thenReturn(2);
            flows.add(flow);
        }

        pipeline.process(new Source("here", InetAddress.getLoopbackAddress()), flows);

        assertThat(repository.count()).isEqualTo(25);
        assertThat(counting.captures).hasValue(1);
    }

    /** Counts snapshot captures, the only place a per-flow read is visible. */
    private static final class CountingInventory extends Inventory {
        private final java.util.concurrent.atomic.AtomicInteger captures =
                new java.util.concurrent.atomic.AtomicInteger();
        private final Inventory delegate;

        private CountingInventory(final Inventory delegate) {
            super(new SnmpProfilesConfig(Map.of(), Map.of()), new InventoryConfig());
            this.delegate = delegate;
        }

        @Override
        public org.riptide.inventory.InventorySnapshot snapshot() {
            this.captures.incrementAndGet();
            return this.delegate.snapshot();
        }
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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(interfaceSource, inventory(), emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(0);
        when(flow.getOutputSnmp()).thenReturn(0);

        final var source = new Source("here", InetAddress.getLoopbackAddress());

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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(interfaceSource, inventory(), emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(3);
        when(flow.getOutputSnmp()).thenReturn(2);

        final var source = new Source("here", InetAddress.getLoopbackAddress());
        pipeline.process(source, List.of(flow));

        final var targetIp = InetAddress.getLoopbackAddress();
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

        final var source = new Source("here", InetAddress.getLoopbackAddress());

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

        final var enrichers = List.<Enricher>of(new SnmpEnricher(liveSnmp(), inventory(), interfaceTable));
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
