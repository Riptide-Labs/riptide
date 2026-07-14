/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Shared flow builder for the multi-tenant ClickHouse ITs ({@link TenantWriteBarrierIT},
 * {@link TenantQueryIsolationIT}). A single fixture so the {@link EnrichedFlow} shape lives in one
 * place — when a required column is added, both ITs pick it up from here rather than drifting.
 */
final class ClickhouseItFlows {

    private ClickhouseItFlows() {
    }

    /** A fully-populated enriched flow stamped with the given identity and source port. */
    static EnrichedFlow flow(final String tenant, final String organisation, final int srcPort) throws Exception {
        final var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return EnrichedFlow.builder()
                .receivedAt(now)
                .timestamp(now)
                .firstSwitched(now.minusSeconds(10))
                .deltaSwitched(now.minusSeconds(10))
                .lastSwitched(now)
                .flowProtocol(Flow.FlowProtocol.IPFIX)
                .tenant(tenant)
                .organisation(organisation)
                .zone("default")
                .system("default")
                .exporterAddr("203.0.113.7")
                .srcAddr(InetAddress.getByName("192.0.2.10"))
                .srcPort(srcPort)
                .srcAs(64512L)
                .srcMaskLen(24)
                .dstAddr(InetAddress.getByName("198.51.100.20"))
                .dstPort(443)
                .dstAs(64513L)
                .dstMaskLen(24)
                .inputSnmp(1)
                .outputSnmp(2)
                .bytes(1234L)
                .packets(7L)
                .direction(Flow.Direction.INGRESS)
                .engineId(0)
                .engineType(0)
                .vlan(0)
                .ipProtocolVersion(4)
                .protocol(17)
                .tcpFlags(0)
                .tos(0)
                .samplingAlgorithm(Flow.SamplingAlgorithm.Unassigned)
                .samplingInterval(1.0)
                .srcLocality(Flow.Locality.PUBLIC)
                .dstLocality(Flow.Locality.PUBLIC)
                .flowLocality(Flow.Locality.PUBLIC)
                .build();
    }
}
