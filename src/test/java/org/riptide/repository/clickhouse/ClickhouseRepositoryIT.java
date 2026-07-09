/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * First real-ClickHouse test of the repository: schema creation on a fresh
 * server, batch insert, and query-back of the persisted values.
 */
@Testcontainers
public class ClickhouseRepositoryIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static ClickhouseRepository repository;
    private static Client queryClient;

    @BeforeAll
    static void setUp() {
        final var config = new ClickhouseConfig();
        config.endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        config.username = "riptide";
        config.password = "riptide";

        repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config);
        repository.start();

        queryClient = new Client.Builder()
                .addEndpoint(config.endpoint)
                .setUsername(config.username)
                .setPassword(config.password)
                .setDefaultDatabase(config.database)
                .build();
    }

    @Test
    void verifyPersistedFlowsAreQueryable() throws Exception {
        final var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.persist(List.of(
                testFlow(now, 10001, 443, 1234L),
                testFlow(now, 10002, 53, 5678L)));

        final var count = queryClient.queryAll("SELECT count() AS c FROM flows").getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(2);

        final var rows = queryClient.queryAll(
                "SELECT srcPort, dstPort, bytes, flowProtocol, exporterAddr FROM flows ORDER BY srcPort");
        Assertions.assertThat(rows).hasSize(2);
        Assertions.assertThat(rows.getFirst().getInteger("srcPort")).isEqualTo(10001);
        Assertions.assertThat(rows.getFirst().getInteger("dstPort")).isEqualTo(443);
        Assertions.assertThat(rows.getFirst().getLong("bytes")).isEqualTo(1234L);
        Assertions.assertThat(rows.getFirst().getString("flowProtocol")).isEqualTo("IPFIX");
        Assertions.assertThat(rows.getFirst().getString("exporterAddr")).isEqualTo("203.0.113.7");
    }

    private static EnrichedFlow testFlow(final Instant now, final int srcPort, final int dstPort, final long bytes) throws Exception {
        return EnrichedFlow.builder()
                .receivedAt(now)
                .timestamp(now)
                .firstSwitched(now.minusSeconds(10))
                .deltaSwitched(now.minusSeconds(10))
                .lastSwitched(now)
                .flowProtocol(Flow.FlowProtocol.IPFIX)
                .location("default")
                .exporterAddr("203.0.113.7")
                .srcAddr(InetAddress.getByName("192.0.2.10"))
                .srcPort(srcPort)
                .srcAs(64512L)
                .srcMaskLen(24)
                .dstAddr(InetAddress.getByName("198.51.100.20"))
                .dstPort(dstPort)
                .dstAs(64513L)
                .dstMaskLen(24)
                .inputSnmp(1)
                .outputSnmp(2)
                .bytes(bytes)
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
