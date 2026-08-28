/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ConnectionInitiationException;
import com.clickhouse.client.api.ServerException;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Throwables;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.e2e.ContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * First real-ClickHouse test of the repository: schema creation on a fresh
 * server, batch insert, and query-back of the persisted values.
 */
@Testcontainers
public class ClickhouseRepositoryIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
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
        // Read-after-write assertions need synchronous inserts; async has its own test below.
        config.setAsyncInserts(false);

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
    void rollupsConserveTotalsAndKeepUndirectedTrafficVisible() throws Exception {
        final var repo = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor("rollups", true), RESOLVERS);
        repo.start();

        // Three flows in one minute bucket: one INGRESS, one EGRESS, one UNKNOWN. The UNKNOWN one
        // is the point — it belongs to neither side of the split, so a rollup that only carried
        // bytesIn/bytesOut would lose it entirely.
        final var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES);
        final var ingress = testFlow(minute.plusSeconds(5), 40001, 443, 100L);
        final var egress = testFlow(minute.plusSeconds(20), 40002, 443, 200L);
        egress.setDirection(Flow.Direction.EGRESS);
        final var unknown = testFlow(minute.plusSeconds(40), 40003, 443, 800L);
        unknown.setDirection(Flow.Direction.UNKNOWN);
        repo.persist(List.of(ingress, egress, unknown));

        final var rawBytes = queryClient.queryAll("SELECT sum(bytes) AS b FROM rollups.flows")
                .getFirst().getLong("b");
        Assertions.assertThat(rawBytes).isEqualTo(1100L);

        // Every rollup must conserve the raw totals — no double counting, no dropped rows.
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final var totals = queryClient.queryAll(
                    "SELECT sum(bytes) AS b, sum(packets) AS p, sum(flowCount) AS c FROM rollups." + rollup)
                    .getFirst();
            Assertions.assertThat(totals.getLong("b")).as("%s bytes", rollup).isEqualTo(1100L);
            Assertions.assertThat(totals.getLong("p")).as("%s packets", rollup).isEqualTo(21L);
            Assertions.assertThat(totals.getLong("c")).as("%s flow count", rollup).isEqualTo(3L);
        }

        // The directional split covers only the directed flows; the undirected total covers all.
        final var split = queryClient.queryAll("SELECT sum(bytesIn) AS in, sum(bytesOut) AS out, "
                + "sum(bytes) AS total FROM rollups.flows_by_application_1m").getFirst();
        Assertions.assertThat(split.getLong("in")).isEqualTo(100L);
        Assertions.assertThat(split.getLong("out")).isEqualTo(200L);
        Assertions.assertThat(split.getLong("total")).isEqualTo(1100L);

        // All three land in one bucket: toStartOfMinute really is truncating.
        final var buckets = queryClient.queryAll(
                "SELECT timestamp, sum(bytes) AS b FROM rollups.flows_by_application_1m GROUP BY timestamp");
        Assertions.assertThat(buckets).hasSize(1);
        Assertions.assertThat(buckets.getFirst().getLong("b")).isEqualTo(1100L);
    }

    @Test
    void asyncInsertsOptInStillFeedsTheRollups() throws Exception {
        // Async inserts default off while batching is enabled, since batching supersedes them
        // (ClickhouseConfig#isAsyncInserts); the coalesced path stays available as an opt-in. The
        // insert is acknowledged when buffered, so visibility is eventual — the flush is forced
        // here to keep the test deterministic; production readers poll (dashboards), they do not
        // read-after-write.
        final var config = configFor("async_mode", true);
        config.setAsyncInserts(null);
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
        config.setAsyncInserts(true);

        final var repo = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repo.start();
        repo.persist(List.of(
                testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 50001, 443, 300L),
                testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 50002, 443, 700L)));

        queryClient.execute("SYSTEM FLUSH ASYNC INSERT QUEUE").get();

        Assertions.assertThat(queryClient.queryAll("SELECT sum(bytes) AS b FROM async_mode.flows")
                .getFirst().getLong("b")).isEqualTo(1000L);
        // The rollup views fire on the coalesced flush, not per client call — totals must conserve.
        Assertions.assertThat(queryClient.queryAll(
                        "SELECT sum(bytes) AS b FROM async_mode.flows_by_application_1m")
                .getFirst().getLong("b")).isEqualTo(1000L);
    }

    @Test
    void batchingDecoratorDrainsAcceptedFlowsOnStopAndFeedsRollups() throws Exception {
        final var config = configFor("batching", true);
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        // maxRows stays at the production 10k so the size trigger never fires for a handful of
        // flows — delivery rides the time trigger and the shutdown drain. maxLatency is small and
        // the grace generous (vs the production 2s/5s) so a cold first insert on slow CI cannot
        // outlive the grace mid-drain.
        config.getBatch().setMaxLatency(Duration.ofMillis(500));
        config.getBatch().setShutdownGracePeriod(Duration.ofSeconds(15));
        final var batching = new BatchingFlowRepository(repository, config.getBatch(), new MetricRegistry());
        batching.start();

        final var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.MINUTES);
        for (int i = 0; i < 5; i++) {
            // Singleton lists, like the production dispatcher hands the persister.
            batching.persist(List.of(testFlow(minute.plusSeconds(i), 20001 + i, 443, 100L)));
        }
        batching.stop();

        // stop() drained: every accepted row is queryable once it returns.
        final var count = queryClient.queryAll("SELECT count() AS c FROM batching.flows")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(5);

        // The batched insert fired the rollup materialized views (one target as the canary).
        final var rollupBytes = queryClient.queryAll(
                        "SELECT sum(bytes) AS b FROM batching.flows_by_application_1m")
                .getFirst().getLong("b");
        Assertions.assertThat(rollupBytes).isEqualTo(500L);
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

    @Test
    void samplesViewClampsDegenerateFlowsAndHidesHelperColumns() throws Exception {
        final var database = "samples_guard";
        final var repo = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS);
        repo.start();

        // The two degenerate cases the view's guards exist for: a zero-duration flow (the
        // bucket_count = 1 branch is its division guard) and a corrupt flow with
        // lastSwitched < deltaSwitched (the greatest() clamp — unclamped, bucket_count wraps and
        // range() throws, poisoning every query over the view). Each must land in exactly one
        // bucket with its full totals.
        final var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(10, ChronoUnit.MINUTES);
        repo.persist(List.of(
                testFlow(minute.plusSeconds(15), minute.plusSeconds(15), 40011, 443, 500L, 5L),
                testFlow(minute.plusSeconds(75), minute.plusSeconds(70), 40012, 443, 700L, 7L)));

        final var totals = queryClient.queryAll(
                "SELECT count() AS n, sum(bytes) AS b, sum(packets) AS p FROM " + database + ".samples(ival = 60)");
        Assertions.assertThat(totals.getFirst().getLong("n")).isEqualTo(2L);
        Assertions.assertThat(totals.getFirst().getDouble("b")).isCloseTo(1200.0, Assertions.within(0.01));
        Assertions.assertThat(totals.getFirst().getDouble("p")).isCloseTo(12.0, Assertions.within(0.01));

        // The view's output contract, derived rather than enumerated: every flows column in
        // order — with the shadowed trio surfacing under flow.-qualified names — followed by the
        // bucketed timestamp/bytes/packets. Comparing the full DESCRIBE catches a leaked helper
        // scalar whatever its name (an enumerated negative list would stay green after a rename),
        // and equally catches an EXCEPT entry accidentally hiding a real flows column.
        final var shadowed = Set.of("timestamp", "bytes", "packets");
        final var expected = new ArrayList<String>();
        queryClient.queryAll("SELECT name FROM system.columns WHERE database = '" + database
                        + "' AND table = 'flows' ORDER BY position")
                .forEach(row -> {
                    final var name = row.getString("name");
                    expected.add(shadowed.contains(name) ? "flow." + name : name);
                });
        expected.addAll(List.of("timestamp", "bytes", "packets"));
        final var actual = queryClient.queryAll(
                        "DESCRIBE (SELECT * FROM " + database + ".samples(ival = 60))").stream()
                .map(row -> row.getString("name"))
                .toList();
        Assertions.assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void additiveColumnsPersistAndAnOlderTableUpgradesInPlace() throws Exception {
        final var database = "geo_upgrade";
        final var config = configFor(database, true);
        final var repo = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repo.start();

        // start() created the rollup views, and ClickHouse refuses to DROP a column a materialized
        // view references (ALTER_OF_COLUMN_IS_FORBIDDEN) — drop the views first so the pre-geo
        // simulation below can strip the columns. A real pre-geo database has neither.
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            queryClient.execute("DROP VIEW IF EXISTS " + FlowsSchema.qualifiedRollupView(database, rollup)).get();
        }

        // Simulate an older table: keep a persisted row, then drop every additive column. Driven
        // off additiveColumnNames() rather than a literal list, so a column added to the additive
        // set is covered here without editing this test.
        repo.persist(List.of(testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 60001, 443, 100L)));
        for (final String column : FlowsSchema.additiveColumnNames()) {
            queryClient.execute("ALTER TABLE " + database + ".flows DROP COLUMN " + column).get();
        }

        // Validate mode never alters: it fails fast naming the geo columns and the onboard fix.
        final var validating = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), configFor(database, false), RESOLVERS);
        Assertions.assertThatThrownBy(validating::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exporterName")
                .hasMessageContaining("riptide onboard");

        // Manage mode adds the columns back in place; the older row survives and reads ''.
        final var upgraded = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        Assertions.assertThatCode(upgraded::start).doesNotThrowAnyException();
        final var legacy = queryClient.queryAll(
                "SELECT srcCountry, samplingProvenance FROM " + database + ".flows WHERE srcPort = 60001");
        Assertions.assertThat(legacy.getFirst().getString("srcCountry")).isEmpty();
        // '' is the marker for "written before the column existed" — distinct from 'assumed',
        // which is a resolution riptide actually made, and not backfillable into these rows.
        Assertions.assertThat(legacy.getFirst().getString("samplingProvenance")).isEmpty();

        // An enriched flow round-trips its geo fields.
        final var geoFlow = testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 60002, 443, 100L);
        geoFlow.setSrcCountry("DE");
        geoFlow.setSrcCity("Fulda");
        geoFlow.setDstCountry("US");
        geoFlow.setDstCity("Ashburn");
        upgraded.persist(List.of(geoFlow));

        final var row = queryClient.queryAll(
                "SELECT srcCountry, srcCity, dstCountry, dstCity FROM " + database
                        + ".flows WHERE srcPort = 60002").getFirst();
        Assertions.assertThat(row.getString("srcCountry")).isEqualTo("DE");
        Assertions.assertThat(row.getString("srcCity")).isEqualTo("Fulda");
        Assertions.assertThat(row.getString("dstCountry")).isEqualTo("US");
        Assertions.assertThat(row.getString("dstCity")).isEqualTo("Ashburn");
    }

    /** Every rung round-trips to a readable token, and the two 1.0 rows stay distinguishable. */
    @Test
    void everySamplingProvenanceRoundTripsThroughTheColumn() throws Exception {
        final var database = "provenance_round_trip";
        final var repo = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS);
        repo.start();

        int port = 61000;
        final var expected = new LinkedHashMap<Integer, Flow.SamplingProvenance>();
        for (final var provenance : Flow.SamplingProvenance.values()) {
            final var flow = testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), port, 443, 100L);
            flow.setSamplingProvenance(provenance);
            // Two rungs legitimately produce 1.0; the column is what separates them.
            flow.setSamplingInterval(provenance == Flow.SamplingProvenance.Assumed ? 1.0 : 1000.0);
            repo.persist(List.of(flow));
            expected.put(port, provenance);
            port++;
        }

        for (final var entry : expected.entrySet()) {
            final var row = queryClient.queryAll("SELECT samplingProvenance FROM " + database
                    + ".flows WHERE srcPort = " + entry.getKey()).getFirst();
            Assertions.assertThat(row.getString("samplingProvenance"))
                    .describedAs("provenance for %s", entry.getValue())
                    .isEqualTo(entry.getValue().token());
        }
    }

    private static ClickhouseConfig configFor(final String database, final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(manageSchema);
        // These tests assert read-after-write; the coalesced path gets its own dedicated test.
        config.setAsyncInserts(false);
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
                .samplingProvenance(Flow.SamplingProvenance.Assumed)
                .srcLocality(Flow.Locality.PUBLIC)
                .dstLocality(Flow.Locality.PUBLIC)
                .flowLocality(Flow.Locality.PUBLIC)
                .build();
    }

    // ---- #548 probe: PQ-5 atomicity, PQ-6 rejected row vs transient failure -------------------

    /** Its own database, so the constraint below cannot disturb any fixture above. */
    private static final String POISON_DB = "poison_probe";

    /** The tenant the probe's CHECK constraint admits. */
    private static final String GOOD_TENANT = "ok";

    /** A tenant the constraint refuses — one row of a batch carrying this poisons the whole insert. */
    private static final String BAD_TENANT = "rejected";

    /** Where the poison row sits, 1-based, so the reported index can be checked rather than guessed. */
    private static final int POISON_POSITION = 5;
    private static final int BATCH_SIZE = 6;

    /** A started repository against {@link #POISON_DB} and a client to count its rows with. */
    private record PoisonProbe(ClickhouseRepository repository, Client client) implements AutoCloseable {
        @Override
        public void close() {
            this.client.close();
        }
    }

    private static PoisonProbe provisionPoisonProbe() throws Exception {
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor(POISON_DB, true), RESOLVERS);
        repository.start();
        final var client = new Client.Builder().addEndpoint(configFor(POISON_DB, true).getEndpoint())
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase(POISON_DB).build();
        client.execute("ALTER TABLE " + FlowsSchema.qualifiedFlows(POISON_DB)
                + " ADD CONSTRAINT probe_tenant CHECK tenant = '" + GOOD_TENANT + "'").get();
        return new PoisonProbe(repository, client);
    }

    /** Rows currently in the probe's base table and in every rollup target, keyed by table. */
    private static Map<String, Long> poisonRowCounts(final Client client) throws Exception {
        // Named, not read back from the schema: a list sized against itself can only agree with
        // itself, and a rollup dropped from the schema would then drop out of "nothing landed
        // anywhere" without a word.
        Assertions.assertThat(FlowsSchema.rollupTableNames())
                .as("the probe must count the base table AND every rollup, or 'nothing landed' is a"
                        + " statement about one table while the others go unchecked")
                .containsExactlyInAnyOrder(FlowsSchema.ROLLUP_BY_APPLICATION,
                        FlowsSchema.ROLLUP_BY_CONVERSATION, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE,
                        FlowsSchema.ROLLUP_BY_GEO_ASN);
        final List<String> tables = new ArrayList<>();
        tables.add(FlowsSchema.qualifiedFlows(POISON_DB));
        FlowsSchema.rollupTableNames().forEach(r -> tables.add(FlowsSchema.qualifiedRollup(POISON_DB, r)));
        final Map<String, Long> counts = new LinkedHashMap<>();
        for (final String table : tables) {
            try (var rows = client.queryRecords("SELECT count() AS c FROM " + table).get()) {
                for (final var row : rows) {
                    counts.put(table, row.getLong("c"));
                }
            }
        }
        return counts;
    }

    private static List<EnrichedFlow> batchWithPoisonAt(final int position) throws Exception {
        final List<EnrichedFlow> batch = new ArrayList<>();
        for (int i = 1; i <= BATCH_SIZE; i++) {
            batch.add(ClickhouseItFlows.flow(i == position ? BAD_TENANT : GOOD_TENANT, "org", 20000 + i));
        }
        return batch;
    }

    /**
     * What one rejected row does to the batch around it, and whether the refusal is legible (#548).
     *
     * <p>#548 must choose between bisecting a poisoned batch and dead-lettering it, and both rest on
     * facts nobody had measured: whether a refused insert can leave a partial write — in which case
     * re-inserting a bisected half double-counts — and whether a rejected row is distinguishable from
     * a dropped connection, which {@code BatchingFlowRepository.flush} currently cannot tell apart.</p>
     *
     * <p>Asked through {@code ClickhouseRepository.persist}, not raw SQL: the question is what
     * riptide's own path does, and a probe issuing its own INSERT would measure a path production
     * never takes.</p>
     *
     * <p><b>What this is not.</b> A regression test against the server pinned in
     * {@code .github/e2e-images/clickhouse.Dockerfile}, not a proof. It says the batch behaves this
     * way on the ClickHouse it runs against, which is what makes a version bump surface a change
     * rather than let #548's design rest on a stale measurement. It does not implement #548, and it
     * says nothing about whether riptide can map a reported row index back to its own batch.</p>
     */
    @Test
    void aRejectedRowTakesTheWholeBatchWithItAndSaysWhichRowItWas() throws Exception {
        try (var probe = provisionPoisonProbe()) {
            // A clean batch first: if the constraint refused everything, every "nothing landed"
            // assertion below would pass while proving nothing at all. Counted as a delta, because
            // the transport probe shares this database and may have landed its own row first.
            final long before = poisonRowCounts(probe.client()).get(FlowsSchema.qualifiedFlows(POISON_DB));
            probe.repository().persist(List.of(ClickhouseItFlows.flow(GOOD_TENANT, "org", 19999)));
            final Map<String, Long> afterHealthy = poisonRowCounts(probe.client());
            Assertions.assertThat(afterHealthy.get(FlowsSchema.qualifiedFlows(POISON_DB)))
                    .as("a healthy row must land, or the fixture rejects everything and asserts nothing")
                    .isEqualTo(before + 1);

            final Throwable refusal = catchThrowable(
                    () -> probe.repository().persist(batchWithPoisonAt(POISON_POSITION)));

            Assertions.assertThat(refusal)
                    .as("a batch carrying a constraint-violating row must be refused, not silently kept")
                    .isNotNull();
            final ServerException server = serverExceptionIn(refusal);
            Assertions.assertThat(server)
                    .as("the refusal must carry a ServerException, or no error code is readable and this"
                            + " probe settles nothing: %s", refusal)
                    .isNotNull();

            // PQ-5: atomic across the base table and every rollup behind it.
            Assertions.assertThat(poisonRowCounts(probe.client()))
                    .as("PQ-5 [#548] on ClickHouse %s: a refused batch must leave no row anywhere, or a"
                            + " bisect could re-insert a half that partly landed and double-count",
                            serverVersionOf(probe.client()))
                    .isEqualTo(afterHealthy);

            // PQ-6, half one: the code a rejected row answers.
            Assertions.assertThat(server.getCode())
                    .as("PQ-6 [#548]: rejected-row code on ClickHouse %s is %d",
                            serverVersionOf(probe.client()), server.getCode())
                    .isEqualTo(REJECTED_ROW_CODE);

            // The offending row is named, so #548 may need neither a bisect nor a dead-letter.
            Assertions.assertThat(server.getMessage())
                    .as("the refusal names which row offended, at the position the batch put it")
                    .contains("violated at row " + POISON_POSITION);
        }
    }

    /**
     * A TCP relay in front of the container, so a started repository can lose its transport
     * without the shared container being paused or stopped under every other test in this class.
     *
     * <p>Closing it refuses new connections and resets the ones already relayed, which is the
     * shape of a ClickHouse that went away mid-run. A paused container would measure the same
     * thing, but only after the client's socket timeout, multiplied by its retry count.</p>
     */
    private static final class Relay implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        Relay(final String targetHost, final int targetPort) throws IOException {
            this.listener = new ServerSocket(0);
            Thread.ofVirtual().start(() -> {
                while (!this.listener.isClosed()) {
                    try {
                        final Socket inbound = this.listener.accept();
                        final Socket outbound = new Socket(targetHost, targetPort);
                        this.sockets.add(inbound);
                        this.sockets.add(outbound);
                        pump(inbound, outbound);
                        pump(outbound, inbound);
                    } catch (final IOException closed) {
                        return;
                    }
                }
            });
        }

        private static void pump(final Socket from, final Socket to) {
            Thread.ofVirtual().start(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (final IOException ignored) {
                    // The relay was closed under it; nothing to forward any more.
                }
            });
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            this.listener.close();
            for (final Socket socket : this.sockets) {
                socket.close();
            }
        }
    }

    /**
     * A lost transport answers a different failure from a refused row, so #548 can branch (#548).
     *
     * <p>The half that matters: if the two were equal, quarantining a poison row would quarantine a
     * network blip too, and the 10,000 rows it carried would be discarded rather than retried.</p>
     *
     * <p>Measured on {@code persist}, the call {@code BatchingFlowRepository.flush} branches on, from
     * a repository that started healthy: a refused connect at bootstrap is a different path and may
     * surface a different exception.</p>
     */
    @Test
    void aTransientFailureIsDistinguishableFromARejectedRow() throws Exception {
        final Throwable transientFailure;
        try (var relay = new Relay(CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123))) {
            final var config = configFor(POISON_DB, true);
            config.setEndpoint(relay.endpoint());
            final var repository = new ClickhouseRepository(
                    new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
            repository.start();
            // Through the relay first, so the failure below is the relay closing and not a relay
            // that never worked.
            repository.persist(List.of(ClickhouseItFlows.flow(GOOD_TENANT, "org", 19998)));

            relay.close();
            transientFailure = catchThrowable(() ->
                    repository.persist(List.of(ClickhouseItFlows.flow(GOOD_TENANT, "org", 19997))));
        }

        Assertions.assertThat(transientFailure)
                .as("a repository whose server went away must fail, or this test compares nothing")
                .isNotNull();
        Assertions.assertThat(serverExceptionIn(transientFailure))
                .as("PQ-6 [#548]: a lost transport must not surface as a ServerException, or the"
                        + " rejected-row code %d is not the only thing a branch could key on: %s",
                        REJECTED_ROW_CODE, transientFailure)
                .isNull();
        // Observed: the client's ConnectionInitiationException is the top of the chain, not a
        // cause under FlowException. It is unchecked, so persist's ExecutionException wrap never
        // sees it; a branch in BatchingFlowRepository.flush has to catch it separately.
        Assertions.assertThat(Throwables.getCausalChain(transientFailure))
                .as("PQ-6 [#548]: a lost transport surfaces the client's transport exception, which"
                        + " is what a retry-versus-quarantine branch keys on: %s", transientFailure)
                .hasAtLeastOneElementOfType(ConnectionInitiationException.class);
    }

    /**
     * The code a constraint-violating row answers, read off the server rather than predicted.
     *
     * <p>A literal because client-v2 0.10.0 names no enum member for it.</p>
     */
    private static final int REJECTED_ROW_CODE = 469;

    /**
     * The first {@link ServerException} in a cause chain, or null when there is none.
     *
     * <p>Walked with Guava rather than by hand: {@code getCausalChain} bounds the walk and rejects a
     * cycle itself, where a hand-rolled loop needs a reference comparison Error Prone refuses.</p>
     */
    private static ServerException serverExceptionIn(final Throwable thrown) {
        return Throwables.getCausalChain(thrown).stream()
                .filter(ServerException.class::isInstance)
                .map(ServerException.class::cast)
                .findFirst()
                .orElse(null);
    }

    /** The server that answered, read from it rather than from the image tag. */
    private static String serverVersionOf(final Client client) throws Exception {
        try (var rows = client.queryRecords("SELECT version() AS v").get()) {
            for (final var row : rows) {
                return row.getString("v");
            }
        }
        throw new AssertionError("version() returned no rows");
    }
}
