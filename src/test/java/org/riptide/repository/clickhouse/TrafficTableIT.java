/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.TrafficSchema;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code riptide.clickhouse.traffic-table} against a real server: what the switch creates, that
 * {@code sum(bytes)} is the sampling-corrected total, and that the rollups spread a flow over its
 * minutes without losing or inventing a byte.
 */
@Testcontainers
class TrafficTableIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    /** A minute boundary inside both retentions, so no TTL merge can remove what a test inserts. */
    private static final Instant MINUTE = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.DAYS);

    private static Client query;

    @BeforeAll
    static void setUp() {
        query = new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername("riptide")
                .setPassword("riptide")
                .build();
    }

    @AfterAll
    static void tearDown() {
        query.close();
    }

    /**
     * One query, correct for every protocol, and equal to what {@code flows} needs a protocol rule
     * for. The sFlow row arrives scaled from its receiver; the IPFIX row arrives sampled.
     */
    @Test
    void sumOfBytesIsTheSamplingCorrectedTotalForEveryProtocol() throws Exception {
        final var repository = started("traffic_sum");
        repository.persist(List.of(
                flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE, 1000L, 2L, 100.0),
                flow(Flow.FlowProtocol.SFLOW, MINUTE, MINUTE, 1500L * 512, 512L, 512.0),
                flow(Flow.FlowProtocol.NetflowV5, MINUTE, MINUTE, 700L, 1L, null)));

        final GenericRecord traffic = query.queryAll("SELECT sum(bytes) AS b, sum(bytes_reported) AS r,"
                + " sum(packets) AS p FROM traffic_sum.traffic").getFirst();
        final long corrected = query.queryAll("SELECT sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval))"
                + " AS b FROM traffic_sum.flows").getFirst().getLong("b");

        assertThat(traffic.getLong("b")).isEqualTo(100_000L + 768_000L + 700L).isEqualTo(corrected);
        assertThat(traffic.getLong("r")).isEqualTo(1000L + 1500L + 700L);
        assertThat(traffic.getLong("p")).isEqualTo(200L + 512L + 1L);
    }

    /**
     * A flow covering 0:30 to 2:45 has 30 s, 60 s and 45 s in three minutes. Each minute gets the
     * integer share of its time, the shares add up to the flow exactly, and the flow counts once,
     * in the minute it ended.
     */
    @Test
    void rollupsSpreadAFlowOverItsMinutesAndKeepTheTotalExact() throws Exception {
        final var repository = started("traffic_spread");
        repository.persist(List.of(
                flow(Flow.FlowProtocol.IPFIX, MINUTE.plusSeconds(30), MINUTE.plusSeconds(165), 1_000_003L, 17L, null),
                // Ends exactly on a minute boundary: all of it belongs to the two minutes before.
                flow(Flow.FlowProtocol.IPFIX, MINUTE.plusSeconds(420), MINUTE.plusSeconds(540), 600L, 6L, null),
                // An exporter clock claiming a year: lands whole in its start minute, not 525,600 rows.
                flow(Flow.FlowProtocol.IPFIX, MINUTE.plusSeconds(1200), MINUTE.plusSeconds(1200).plus(365,
                        ChronoUnit.DAYS), 999L, 9L, null)));

        final var minutes = query.queryAll("SELECT dateDiff('second', toDateTime(" + MINUTE.getEpochSecond()
                + ", 'UTC'), time) AS offset, sum(bytes) AS b, sum(packets) AS p, sum(flows) AS f"
                + " FROM traffic_spread." + TrafficSchema.ROLLUP_BY_INTERFACE
                + " GROUP BY time ORDER BY time");

        assertThat(minutes).extracting(row -> List.of(row.getLong("offset"), row.getLong("b"),
                        row.getLong("p"), row.getLong("f")))
                .containsExactly(
                        List.of(0L, 222_222L, 3L, 0L),
                        List.of(60L, 444_446L, 8L, 0L),
                        List.of(120L, 333_335L, 6L, 1L),
                        List.of(420L, 300L, 3L, 0L),
                        List.of(480L, 300L, 3L, 1L),
                        List.of(1200L, 999L, 9L, 1L));

        final GenericRecord raw = query.queryAll("SELECT sum(bytes) AS b, sum(packets) AS p, count() AS f"
                + " FROM traffic_spread.traffic").getFirst();
        for (final String rollup : TrafficSchema.rollupTableNames()) {
            final GenericRecord total = query.queryAll("SELECT sum(bytes) AS b, sum(packets) AS p,"
                    + " sum(flows) AS f FROM traffic_spread." + rollup).getFirst();
            assertThat(List.of(total.getLong("b"), total.getLong("p"), total.getLong("f")))
                    .as(rollup)
                    .containsExactly(raw.getLong("b"), raw.getLong("p"), raw.getLong("f"));
        }
    }

    @Test
    void theRowCarriesTheContractValues() throws Exception {
        final var repository = started("traffic_row");
        repository.persist(List.of(flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE.plusSeconds(5), 10L, 1L, 4.0)));

        final GenericRecord row = query.queryAll("SELECT toString(exporter_ip) AS exporter,"
                + " toString(src_addr) AS src, flow_protocol, direction, in_if_name, src_as, sampling_source,"
                + " sampling_rate, toUnixTimestamp64Milli(time_start) AS start FROM traffic_row.traffic").getFirst();

        assertThat(row.getString("exporter")).isEqualTo("::ffff:203.0.113.7");
        assertThat(row.getString("src")).isEqualTo("::ffff:192.0.2.10");
        assertThat(row.getString("flow_protocol")).isEqualTo("ipfix");
        assertThat(row.getString("direction")).isEqualTo("ingress");
        assertThat(row.getString("in_if_name")).isEmpty();
        assertThat(row.getLong("src_as")).isEqualTo(64512L);
        assertThat(row.getString("sampling_source")).isEqualTo("record");
        assertThat(row.getDouble("sampling_rate")).isEqualTo(4.0);
        assertThat(row.getLong("start")).isEqualTo(MINUTE.toEpochMilli());
    }

    @Test
    void aRestartKeepsTheTablesAndTheirRows() throws Exception {
        started("traffic_restart").persist(List.of(flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE, 5L, 1L, null)));

        started("traffic_restart");

        assertThat(count("SELECT count() AS c FROM traffic_restart.traffic")).isEqualTo(1L);
    }

    /** onboard does not row-policy these tables, so a provisioned deployment must not get them. */
    @Test
    void validateModeRefusesToStartWithTheSwitchOn() throws Exception {
        started("traffic_validate", false);
        final var config = config("traffic_validate", true);
        config.setManageSchema(false);

        assertThatThrownBy(() -> new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.clickhouse.traffic-table=true needs riptide.clickhouse.manage-schema=true");
        assertThat(count("SELECT count() AS c FROM system.tables WHERE database = 'traffic_validate'"
                + " AND name LIKE 'traffic%'")).isZero();
    }

    @Test
    void offCreatesNothingAndWritesNothing() throws Exception {
        started("traffic_off", false).persist(List.of(flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE, 5L, 1L, null)));

        assertThat(count("SELECT count() AS c FROM system.tables WHERE database = 'traffic_off'"
                + " AND name LIKE 'traffic%'")).isZero();
        assertThat(count("SELECT count() AS c FROM traffic_off.flows")).isEqualTo(1L);
    }

    /**
     * The flows are already committed to {@code flows} when the second insert runs, so its failure
     * must not reach the caller: the batching flusher would dead-letter rows that were stored.
     */
    @Test
    void aFailedTrafficInsertIsCountedAndDoesNotFailThePersist() throws Exception {
        final var metrics = new MetricRegistry();
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(),
                config("traffic_failing", true), RESOLVERS, metrics);
        repository.start();
        query.execute("DROP TABLE traffic_failing." + TrafficSchema.TRAFFIC).get();

        assertThatCode(() -> repository.persist(List.of(
                flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE, 5L, 1L, null),
                flow(Flow.FlowProtocol.IPFIX, MINUTE, MINUTE, 6L, 1L, null))))
                .doesNotThrowAnyException();

        assertThat(count("SELECT count() AS c FROM traffic_failing.flows")).isEqualTo(2L);
        assertThat(metrics.counter("persister.traffic.failedRows").getCount()).isEqualTo(2L);
    }

    @Test
    void aTrafficTableMissingAColumnStopsTheStart() throws Exception {
        query.execute("CREATE DATABASE IF NOT EXISTS traffic_stale").get();
        query.execute("CREATE TABLE traffic_stale.traffic (tenant String, time_start DateTime64(3, 'UTC'))"
                + " ENGINE = MergeTree ORDER BY tenant").get();

        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(),
                config("traffic_stale", true), RESOLVERS);

        assertThatThrownBy(repository::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("traffic table in database 'traffic_stale' is missing the column(s) [organisation,");
    }

    private static ClickhouseRepository started(final String database) {
        return started(database, true);
    }

    private static ClickhouseRepository started(final String database, final boolean traffic) {
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(),
                config(database, traffic), RESOLVERS);
        repository.start();
        return repository;
    }

    private static ClickhouseConfig config(final String database, final boolean traffic) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setAsyncInserts(false);
        config.setTrafficTable(traffic);
        return config;
    }

    private static long count(final String sql) {
        return query.queryAll(sql).getFirst().getLong("c");
    }

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    private static EnrichedFlow flow(final Flow.FlowProtocol protocol, final Instant start, final Instant end,
                                     final long bytes, final long packets, final Double rate) throws Exception {
        return EnrichedFlow.builder()
                .receivedAt(end)
                .timestamp(end)
                .firstSwitched(start)
                .deltaSwitched(start)
                .lastSwitched(end)
                .flowProtocol(protocol)
                .tenant("default")
                .organisation("default")
                .zone("default")
                .system("default")
                .exporterAddr("203.0.113.7")
                .srcAddr(InetAddress.getByName("192.0.2.10"))
                .srcPort(10001)
                .srcAs(64512L)
                .srcMaskLen(24)
                .dstAddr(InetAddress.getByName("198.51.100.20"))
                .dstPort(443)
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
                .protocol(6)
                .tcpFlags(0)
                .tos(0)
                .samplingAlgorithm(Flow.SamplingAlgorithm.Unassigned)
                .samplingInterval(rate == null ? 1.0 : rate)
                .samplingProvenance(rate == null ? Flow.SamplingProvenance.Assumed : Flow.SamplingProvenance.Record)
                .srcLocality(Flow.Locality.PUBLIC)
                .dstLocality(Flow.Locality.PUBLIC)
                .flowLocality(Flow.Locality.PUBLIC)
                .build();
    }
}
