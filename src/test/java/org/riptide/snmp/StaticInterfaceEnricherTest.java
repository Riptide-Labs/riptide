/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.MetricRegistry;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.flows.parser.data.Flow;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.riptide.repository.TestRepository;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The enrichment ladder's middle rung on its own: an enrichment entry with interface
 * pins and no matching agent range enriches without any reachable agent. After the
 * cutover that is a real combination rather than an accident, because naming and
 * pinning live in a different tree from credentials.
 */
@SpringBootTest
public class StaticInterfaceEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    /** Static pins must resolve with no SNMP data at all, so the ladder's live rung is empty. */
    private final InterfaceSource noSnmp = (endpoint, ifIndex) -> java.util.Optional.empty();

    /** Pins only, and deliberately no agent range: nothing here is ever walked. */
    private Inventory inventory() {
        final var profiles = new SnmpProfilesConfig(Map.of(), Map.of());
        final var inventory = new Inventory(profiles, new InventoryConfig());
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  exporters:
                    static-only:
                      address: 127.0.0.1
                      interfaces:
                        1: { name: eth0, alias: "Uplink to AS64500", high-speed: 10000 }
                        2: { name: lo0 }
                """, "test.yaml"));
        return inventory;
    }

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void staticMappingEnrichesWithoutSnmp() throws Exception {
        final var enrichers = List.<Enricher>of(new SnmpEnricher(this.noSnmp, inventory(), emptyInterfaceTable()));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getInputSnmp()).thenReturn(1);
        when(flow.getOutputSnmp()).thenReturn(2);

        pipeline.process(new Source("here", InetAddress.getLoopbackAddress()), List.of(flow));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getInputSnmpIfName()).isEqualTo("eth0");
            assertThat(enrichedFlow.getInputSnmpIfAlias()).isEqualTo("Uplink to AS64500");
            assertThat(enrichedFlow.getInputSnmpIfSpeed()).isEqualTo(10000L);
            assertThat(enrichedFlow.getOutputSnmpIfName()).isEqualTo("lo0");
            assertThat(enrichedFlow.getOutputSnmpIfAlias()).isNull();
        });
    }

    private static ExporterInterfaceTable emptyInterfaceTable() {
        final SnmpOptionsConfig cacheConfig = new SnmpOptionsConfig();
        cacheConfig.setRetentionMs(60_000);
        return new ExporterInterfaceTable(cacheConfig, new SessionAdmissionConfig(), new MetricRegistry());
    }
}
