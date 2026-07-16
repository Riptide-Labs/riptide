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
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
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

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    private static ClickhouseRepository repository;
    private static Client queryClient;

    @BeforeAll
    static void setUp() {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));

        repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repository.start();

        queryClient = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername("riptide")
                .setPassword("riptide")
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
        final var first = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        first.start();
        first.persist(List.of(testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 30001, 443, 4242L)));

        // Simulated restart: a fresh repository runs start() again.
        final var second = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        second.start();

        // CREATE TABLE IF NOT EXISTS no-oped, so the previously inserted row survived the restart.
        final var count = queryClient.queryAll("SELECT count() AS c FROM " + database + ".flows")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void manageModeCreatesDatabaseOnFreshServer() throws Exception {
        // A database the container did NOT pre-create and no test creates up front: manage mode
        // must create the database itself, not just the flows table (regression for the
        // UNKNOWN_DATABASE failure on a fresh single-node install).
        final var config = configFor("fresh_managed", true);

        final var repo = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        Assertions.assertThatCode(repo::start).doesNotThrowAnyException();

        repo.persist(List.of(testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 40001, 443, 99L)));
        final var count = queryClient.queryAll("SELECT count() AS c FROM fresh_managed.flows")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void validateModeSucceedsWithProvisionedTable() {
        final var database = "validate_ok";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).join();

        // Provision the schema via a manage-mode start, then a validate-mode start must succeed.
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS).start();

        final var validating = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, false), RESOLVERS);
        Assertions.assertThatCode(validating::start).doesNotThrowAnyException();
    }

    @Test
    void validateModeFailsFastWhenTableAbsent() {
        final var database = "validate_missing";
        queryClient.execute("CREATE DATABASE IF NOT EXISTS " + database).join();

        final var validating = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, false), RESOLVERS);
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
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS);
        Assertions.assertThatThrownBy(repository::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void samplesViewConservesBytesAcrossBucketExpansion() throws Exception {
        final var database = "samples_cons";
        final var repo = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS);
        repo.start();

        // The #270 repro: a 90s, 6000-byte, 90-packet flow starting 30s before a bucket boundary.
        // With ival=60 it spans 30s of its first bucket and 60s of its second, so a time-
        // proportional split gives exactly 2000/4000 bytes — and the totals must be conserved
        // (the pre-#270 division by bucket_count returned 1000/2000, half the traffic).
        final var bucketStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(10, ChronoUnit.MINUTES);
        final var start = bucketStart.plusSeconds(30);
        final var end = bucketStart.plusSeconds(120);
        repo.persist(List.of(testFlow(start, end, 50001, 443, 6000L, 90L)));

        // Exactly two buckets — a flow ending on a bucket boundary must NOT emit a spurious
        // zero-contribution third bucket (boundary shift in the view's last_bucket).
        final var buckets = queryClient.queryAll(
                "SELECT round(bytes, 3) AS b, round(packets, 3) AS p FROM " + database
                        + ".samples(ival = 60) ORDER BY timestamp");
        Assertions.assertThat(buckets).hasSize(2);
        Assertions.assertThat(buckets.get(0).getDouble("b")).isCloseTo(2000.0, Assertions.within(0.01));
        Assertions.assertThat(buckets.get(0).getDouble("p")).isCloseTo(30.0, Assertions.within(0.01));
        Assertions.assertThat(buckets.get(1).getDouble("b")).isCloseTo(4000.0, Assertions.within(0.01));
        Assertions.assertThat(buckets.get(1).getDouble("p")).isCloseTo(60.0, Assertions.within(0.01));

        // Conservation: summing the expansion returns the flow's exact totals.
        final var totals = queryClient.queryAll(
                "SELECT sum(bytes) AS b, sum(packets) AS p FROM " + database + ".samples(ival = 60)");
        Assertions.assertThat(totals.getFirst().getDouble("b")).isCloseTo(6000.0, Assertions.within(0.01));
        Assertions.assertThat(totals.getFirst().getDouble("p")).isCloseTo(90.0, Assertions.within(0.01));
    }

    private static ClickhouseConfig configFor(final String database, final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
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
        return testFlow(now.minusSeconds(10), now, srcPort, dstPort, bytes, 7L);
    }

    private static EnrichedFlow testFlow(final Instant deltaSwitched, final Instant lastSwitched, final int srcPort,
                                         final int dstPort, final long bytes, final long packets) throws Exception {
        return EnrichedFlow.builder()
                .receivedAt(lastSwitched)
                .timestamp(lastSwitched)
                .firstSwitched(deltaSwitched)
                .deltaSwitched(deltaSwitched)
                .lastSwitched(lastSwitched)
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
                .packets(packets)
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
