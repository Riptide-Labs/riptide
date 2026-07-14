/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.riptide.flows.parser.data.Flow;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the name-driven MapStruct mapping of {@link Source} properties into
 * {@link EnrichedFlow}: {@code exporterAddr} and the four identity columns
 * ({@code tenant}, {@code organisation}, {@code zone}, {@code system}) are populated via
 * property-name matching, so a rename on {@link Source} would otherwise fail silently
 * (NULL columns in ClickHouse) instead of loudly.
 */
public class FlowMapperTest {

    private final EnrichedFlow.FlowMapper mapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void sourcePropertiesMapByName() throws Exception {
        final var source = new Source("here", InetAddress.getByName("203.0.113.9"));

        final var enriched = mapper.enrichedFlow(source, mock(Flow.class));

        assertThat(enriched.getExporterAddr()).isEqualTo("203.0.113.9");
        assertThat(enriched.getZone()).isEqualTo("here");
    }

    @Test
    public void identityDefaultsPopulate() throws Exception {
        // The 2-arg convenience constructor defaults the non-zone identity dimensions.
        final var source = new Source("default", InetAddress.getByName("203.0.113.9"));

        final var enriched = mapper.enrichedFlow(source, mock(Flow.class));

        assertThat(enriched.getTenant()).isEqualTo("default");
        assertThat(enriched.getOrganisation()).isEqualTo("default");
        assertThat(enriched.getZone()).isEqualTo("default");
        assertThat(enriched.getSystem()).isEqualTo("default");
    }

    @Test
    public void configuredIdentityIsStamped() throws Exception {
        final var identity = new Identity("acme", "acme-eu", "dmz", "collector-01");
        final var source = new Source(identity, new ExporterIdentity.NetflowIpfix(
                InetAddress.getByName("203.0.113.9"), 7));

        final var enriched = mapper.enrichedFlow(source, mock(Flow.class));

        assertThat(enriched.getTenant()).isEqualTo("acme");
        assertThat(enriched.getOrganisation()).isEqualTo("acme-eu");
        assertThat(enriched.getZone()).isEqualTo("dmz");
        assertThat(enriched.getSystem()).isEqualTo("collector-01");
    }

    @Test
    public void exporterAddrDerivesFromIdentity() throws Exception {
        final var agent = InetAddress.getByName("10.1.1.1");
        final var source = new Source("here", new ExporterIdentity.NetflowIpfix(agent, 42));

        assertThat(source.getExporterAddr()).isEqualTo(agent);
        assertThat(source.identity()).isEqualTo(new ExporterIdentity.NetflowIpfix(agent, 42));
    }
}
