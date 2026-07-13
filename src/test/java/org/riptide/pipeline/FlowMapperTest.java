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
 * {@link EnrichedFlow}: {@code exporterAddr} and {@code location} are populated via
 * property-name matching, so a rename on {@link Source} would otherwise fail silently
 * (NULL exporterAddr in ClickHouse) instead of loudly.
 */
public class FlowMapperTest {

    @Test
    public void sourcePropertiesMapByName() throws Exception {
        final var mapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);
        final var source = new Source("here", InetAddress.getByName("203.0.113.9"));

        final var enriched = mapper.enrichedFlow(source, mock(Flow.class));

        assertThat(enriched.getExporterAddr()).isEqualTo("203.0.113.9");
        assertThat(enriched.getLocation()).isEqualTo("here");
    }

    @Test
    public void exporterAddrDerivesFromIdentity() throws Exception {
        final var agent = InetAddress.getByName("10.1.1.1");
        final var source = new Source("here", new ExporterIdentity.NetflowIpfix(agent, 42));

        assertThat(source.getExporterAddr()).isEqualTo(agent);
        assertThat(source.identity()).isEqualTo(new ExporterIdentity.NetflowIpfix(agent, 42));
    }
}
