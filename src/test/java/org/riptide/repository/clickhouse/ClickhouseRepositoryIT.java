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
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername("riptide");
        config.setPassword("riptide");

        repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config);
        repository.start();

        queryClient = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(config.getUsername())
                .setPassword(config.getPassword())
                .setDefaultDatabase(config.getDatabase())
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
                "SELECT srcPort, dstPort, bytes, flowProtocol, exporterAddr, "
                        + "tenant, organisation, zone, system FROM flows ORDER BY srcPort");
        Assertions.assertThat(rows).hasSize(2);
        Assertions.assertThat(rows.getFirst().getInteger("srcPort")).isEqualTo(10001);
        Assertions.assertThat(rows.getFirst().getInteger("dstPort")).isEqualTo(443);
        Assertions.assertThat(rows.getFirst().getLong("bytes")).isEqualTo(1234L);
        Assertions.assertThat(rows.getFirst().getString("flowProtocol")).isEqualTo("IPFIX");
        Assertions.assertThat(rows.getFirst().getString("exporterAddr")).isEqualTo("203.0.113.7");
        Assertions.assertThat(rows.getFirst().getString("tenant")).isEqualTo("default");
        Assertions.assertThat(rows.getFirst().getString("organisation")).isEqualTo("default");
        Assertions.assertThat(rows.getFirst().getString("zone")).isEqualTo("default");
        Assertions.assertThat(rows.getFirst().getString("system")).isEqualTo("default");
    }

    @Test
    void manageModeCreatesAndPreservesDataAcrossRestart() throws Exception {
        final var database = "manage_restart";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).get();

        final var config = configFor(database, true);

        // First boot: manage mode creates the flows table and we persist a row.
        final var first = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config);
        first.start();
        first.persist(List.of(testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 30001, 443, 4242L)));

        // Simulated restart: a fresh repository runs start() again.
        final var second = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config);
        second.start();

        // CREATE TABLE IF NOT EXISTS no-oped, so the previously inserted row survived the restart.
        final var count = queryClient.queryAll("SELECT count() AS c FROM " + database + ".flows")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void validateModeSucceedsWithProvisionedTable() {
        final var database = "validate_ok";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).join();

        // Provision the schema via a manage-mode start, then a validate-mode start must succeed.
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true)).start();

        final var validating = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, false));
        Assertions.assertThatCode(validating::start).doesNotThrowAnyException();
    }

    @Test
    void validateModeFailsFastWhenTableAbsent() {
        final var database = "validate_missing";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).join();

        final var validating = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, false));
        Assertions.assertThatThrownBy(validating::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flows table not found")
                .hasMessageContaining("provision");
    }

    @Test
    void columnCheckFailsFastWhenIdentityColumnMissing() throws Exception {
        final var database = "stale_schema";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).get();

        // A pre-existing flows table missing the tenant identity column (stale-upgrade case).
        queryClient.execute("CREATE TABLE " + database + ".flows ("
                + "timestamp DateTime64(3), organisation String, zone String, system String) "
                + "ENGINE = MergeTree() ORDER BY timestamp").get();

        // Manage mode: CREATE TABLE IF NOT EXISTS no-ops over the stale table, then the check trips.
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true));
        Assertions.assertThatThrownBy(repository::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant");
    }

    private static ClickhouseConfig configFor(final String database, final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername("riptide");
        config.setPassword("riptide");
        config.setDatabase(database);
        config.setManageSchema(manageSchema);
        return config;
    }

    @Test
    void sortingKeyLeadsWithTenant() {
        final var sortingKey = queryClient.queryAll(
                        "SELECT sorting_key FROM system.tables WHERE name = 'flows'")
                .getFirst().getString("sorting_key");

        // Tenant-led sort key with a rounded-time term; zone/system stay out of it.
        Assertions.assertThat(sortingKey).startsWith("tenant, organisation, toStartOfHour(timestamp)");
        Assertions.assertThat(sortingKey).doesNotContain("zone").doesNotContain("system");
    }

    private static EnrichedFlow testFlow(final Instant now, final int srcPort, final int dstPort, final long bytes) throws Exception {
        return EnrichedFlow.builder()
                .receivedAt(now)
                .timestamp(now)
                .firstSwitched(now.minusSeconds(10))
                .deltaSwitched(now.minusSeconds(10))
                .lastSwitched(now)
                .flowProtocol(Flow.FlowProtocol.IPFIX)
                .tenant("default")
                .organisation("default")
                .zone("default")
                .system("default")
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
