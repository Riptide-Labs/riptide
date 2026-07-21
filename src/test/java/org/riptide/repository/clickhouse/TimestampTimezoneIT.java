/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

/**
 * Flow timestamps must be stored as absolute instants no matter the collector host's timezone.
 * Regression for #276: the columns were {@code java.sql.Timestamp}, which the ClickHouse client
 * encodes from its JVM-local wall clock, so on a non-UTC host every stored value was shifted by
 * the host's UTC offset (a Europe/Berlin production box stored everything +2h).
 *
 * <p>The bug only manifests against a non-UTC ClickHouse server, so this uses a dedicated
 * Europe/Berlin container and runs under a deliberately different JVM default zone
 * (America/New_York) — both non-UTC, neither matching the other — a combination that stored the
 * wrong instant before the {@code OffsetDateTime} fix and stores the exact instant after.
 */
@Testcontainers
public class TimestampTimezoneIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
            .withEnv("TZ", "Europe/Berlin")
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Test
    void storesAbsoluteInstantsRegardlessOfHostTimezone() throws Exception {
        final TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); // -04:00, and != server zone

            final var endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
            final var config = new ClickhouseConfig();
            config.setEndpoint(endpoint);
            config.setUsername(SecretRef.of("riptide"));
            config.setPassword(SecretRef.of("riptide"));
            // Read-after-write assertion; async coalescing has its own test in ClickhouseRepositoryIT.
            config.setAsyncInserts(false);

            // Build the repository after the zone change so its client picks it up.
            final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
            repository.start();

            final var flowEnd = Instant.parse("2026-07-16T12:00:00Z");
            final var flowStart = flowEnd.minusSeconds(30);
            repository.persist(List.of(flow(flowStart, flowEnd)));

            try (Client query = new Client.Builder().addEndpoint(endpoint)
                    .setUsername("riptide").setPassword("riptide").setDefaultDatabase("riptide").build()) {
                // toUnixTimestamp is an absolute epoch — no display-zone ambiguity. Every time column
                // must equal its source instant, not host_offset seconds away from it.
                final var row = query.queryAll(
                        "SELECT toUnixTimestamp(timestamp) AS ts, toUnixTimestamp(receivedAt) AS ra, "
                                + "toUnixTimestamp(deltaSwitched) AS ds, toUnixTimestamp(lastSwitched) AS ls "
                                + "FROM flows").getFirst();
                Assertions.assertThat(row.getLong("ts")).isEqualTo(flowEnd.getEpochSecond());
                Assertions.assertThat(row.getLong("ra")).isEqualTo(flowEnd.getEpochSecond());
                Assertions.assertThat(row.getLong("ds")).isEqualTo(flowStart.getEpochSecond());
                Assertions.assertThat(row.getLong("ls")).isEqualTo(flowEnd.getEpochSecond());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static EnrichedFlow flow(final Instant start, final Instant end) throws Exception {
        return EnrichedFlow.builder()
                .receivedAt(end)
                .timestamp(end)
                .firstSwitched(start)
                .deltaSwitched(start)
                .lastSwitched(end)
                .flowProtocol(Flow.FlowProtocol.IPFIX)
                .tenant("default").organisation("default").zone("default").system("default")
                .exporterAddr("203.0.113.7")
                .srcAddr(InetAddress.getByName("192.0.2.10")).srcPort(1234).srcAs(64512L).srcMaskLen(24)
                .dstAddr(InetAddress.getByName("198.51.100.20")).dstPort(53).dstAs(64513L).dstMaskLen(24)
                .inputSnmp(1).outputSnmp(2)
                .bytes(100L).packets(1L)
                .direction(Flow.Direction.INGRESS)
                .engineId(0).engineType(0).vlan(0)
                .ipProtocolVersion(4).protocol(17).tcpFlags(0).tos(0)
                .samplingAlgorithm(Flow.SamplingAlgorithm.Unassigned).samplingInterval(1.0)
                .srcLocality(Flow.Locality.PUBLIC).dstLocality(Flow.Locality.PUBLIC).flowLocality(Flow.Locality.PUBLIC)
                .build();
    }
}
