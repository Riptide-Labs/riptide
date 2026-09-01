/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;
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

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // That "off" really is a direct insert is pinned by
        // turningAsyncInsertsOffSendsTheSettingRatherThanStayingSilent (#664).
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

    /**
     * Every rung reads back as its own bit through the real materialized view (#581).
     *
     * <p>The rung-to-bit mapping is pinned as SQL text by {@code RollupShapeCheckTest}, but text
     * equality proves the emitted expression, not its effect: a wrong literal bit or a typo'd
     * token ships identically in the emitted and the pinned copy and stays green everywhere. So
     * each rung is driven through the real view and its bit read back — one minute per rung, so
     * the rollup keeps the flows apart without touching any dimension — plus the else arm, which
     * the collector cannot produce: {@code ClickhouseFlow} deliberately defaults an unset
     * provenance to {@code assumed}, and {@code ''} is reserved for rows written before #467. So
     * the else arm is driven the only way it occurs, as a raw row inserted past the mapper.</p>
     *
     * <p>The bits are a deliberate literal, not a read of {@code FlowsSchema}: derived from the
     * thing under test, they would agree with a wrong bit.</p>
     */
    @Test
    void everySamplingProvenanceReadsItsOwnBitThroughTheRollup() throws Exception {
        final var database = "provenance_mask_bits";
        final var repo = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor(database, true), RESOLVERS);
        repo.start();

        final var bits = Map.of(
                Flow.SamplingProvenance.Record, 1L,
                Flow.SamplingProvenance.Options, 2L,
                Flow.SamplingProvenance.Header, 4L,
                Flow.SamplingProvenance.Derived, 8L,
                Flow.SamplingProvenance.Fallback, 16L,
                Flow.SamplingProvenance.Assumed, 32L);
        Assertions.assertThat(bits.keySet())
                .as("every rung the parser can record must be driven, or a new rung ships unprobed")
                .containsExactlyInAnyOrder(Flow.SamplingProvenance.values());

        final var start = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(3600);
        final var minutes = new LinkedHashMap<Flow.SamplingProvenance, Instant>();
        int slot = 0;
        for (final var provenance : Flow.SamplingProvenance.values()) {
            final var minute = start.plusSeconds(60L * slot);
            final var flow = testFlow(minute, 62000 + slot, 443, 100L);
            flow.setSamplingProvenance(provenance);
            repo.persist(List.of(flow));
            minutes.put(provenance, minute);
            slot++;
        }
        final var unrecorded = start.plusSeconds(60L * slot);
        final var bare = testFlow(unrecorded, 62000 + slot, 443, 100L);
        bare.setSamplingProvenance(null);
        repo.persist(List.of(bare));
        final var preProvenance = start.plusSeconds(60L * (slot + 1));
        queryClient.execute("INSERT INTO " + database + ".flows (timestamp, tenant, organisation,"
                + " zone, samplingProvenance) VALUES (fromUnixTimestamp("
                + preProvenance.getEpochSecond() + "), 'default', 'default', 'default', '')").get();

        for (final var entry : minutes.entrySet()) {
            Assertions.assertThat(maskAt(database, entry.getValue()))
                    .describedAs("mask for %s", entry.getKey())
                    .isEqualTo(bits.get(entry.getKey()));
        }
        Assertions.assertThat(maskAt(database, unrecorded))
                .describedAs("a persisted flow with provenance unset is written as 'assumed' by"
                        + " ClickhouseFlow's deliberate column default, never as the else arm")
                .isEqualTo(bits.get(Flow.SamplingProvenance.Assumed));
        Assertions.assertThat(rollupRowsAt(database, preProvenance))
                .describedAs("the raw '' row must have been aggregated into the rollup, or the 0"
                        + " below is groupBitOr over no rows — which also reads 0 — rather than a"
                        + " statement about the else arm")
                .isPositive();
        Assertions.assertThat(maskAt(database, preProvenance))
                .describedAs("'' — the shape of a raw row written before #467, which the collector"
                        + " itself cannot produce — is the multiIf else arm and reads 0")
                .isZero();
    }

    private static long rollupRowsAt(final String database, final Instant minute) {
        return queryClient.queryAll("SELECT count() AS v FROM " + database
                + "." + FlowsSchema.ROLLUP_BY_APPLICATION
                + " WHERE toUnixTimestamp(timestamp) = " + minute.getEpochSecond())
                .getFirst().getLong("v");
    }

    private static long maskAt(final String database, final Instant minute) {
        return queryClient.queryAll("SELECT groupBitOr(samplingProvenanceMask) AS v FROM " + database
                + "." + FlowsSchema.ROLLUP_BY_APPLICATION
                + " WHERE toUnixTimestamp(timestamp) = " + minute.getEpochSecond())
                .getFirst().getLong("v");
    }

    private static ClickhouseConfig configFor(final String database, final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(manageSchema);
        // These tests assert read-after-write; the coalesced path gets its own dedicated test.
        // Pinned by turningAsyncInsertsOffSendsTheSettingRatherThanStayingSilent (#664).
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

    // ---- #664: what the connection actually resolves ------------------------------------------

    /**
     * The value {@code system.query_log} recorded for a setting on the last insert into
     * {@code database}, or the empty string when it recorded nothing.
     *
     * <p>{@code system.query_log.Settings} records the settings whose value <em>differs from the
     * server default</em> — not, as one might assume, every setting the query sent. Measured on the
     * pinned image: sending {@code async_insert=1} against a default of 1 records nothing, while
     * sending {@code 0} records {@code "0"}. Hence the name: this is not the resolved value, it is
     * the value the server recorded as changed.</p>
     *
     * <p>That is still the discriminator this needs, and it is the only one available: a setting
     * riptide never sent reads as absent, exactly as a setting sent at the default does — but "off"
     * is precisely the value that differs from ClickHouse 26.7's default, so the case #664 is about
     * is visible here. The mirror below leans on {@code wait_for_async_insert} for the same
     * reason.</p>
     */
    private static String settingRecordedAsChanged(final String database, final String setting)
            throws Exception {
        queryClient.execute("SYSTEM FLUSH LOGS").get();
        try (var rows = queryClient.queryRecords(
                "SELECT Settings['" + setting + "'] AS v FROM system.query_log"
                        + " WHERE type = 'QueryFinish' AND query_kind = 'Insert'"
                        // current_database, not the query text: the client sends "INSERT INTO flows"
                        // and takes the database from the connection, so the name never appears in
                        // the statement.
                        + " AND current_database = '" + database + "'"
                        + " ORDER BY event_time_microseconds DESC LIMIT 1").get()) {
            for (final var row : rows) {
                return row.getString("v");
            }
        }
        throw new AssertionError("no insert into " + database + " was logged, so nothing was measured");
    }

    /**
     * Both probes below read {@code Settings} as "differs from the server default", so they only
     * mean what they say while the pinned image defaults {@code async_insert} to 1. If a bump of
     * {@code .github/e2e-images/clickhouse.Dockerfile} flips that default, this fails and names the
     * image, instead of the off probe failing and pointing at the repository.
     */
    private static void assumeServerDefaultsAsyncInsertOn() throws Exception {
        Assertions.assertThat(queryClient.queryAll(
                        "SELECT value FROM system.settings WHERE name = 'async_insert'")
                .getFirst().getString("value"))
                .as("the probes below rely on the pinned image defaulting async_insert to 1;"
                        + " if the image changed its default, rework the probes, not the repository")
                .isEqualTo("1");
    }

    private static void persistOneRowInto(final String database, final boolean asyncInserts) throws Exception {
        final var config = configFor(database, true);
        config.setAsyncInserts(asyncInserts);
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repository.start();
        repository.persist(List.of(testFlow(Instant.now().truncatedTo(ChronoUnit.MILLIS), 51001, 443, 7L)));
    }

    /**
     * Turning coalescing off actually turns it off (#664).
     *
     * <p>It did not. {@code ClickhouseRepository} only ever <em>added</em> {@code async_insert=1},
     * so "off" sent nothing and ClickHouse 26.7's own default of {@code async_insert = 1} applied —
     * a third behaviour neither branch of {@code ClickhouseConfig#asyncInserts} describes. Rejections
     * still surfaced, because the server's {@code wait_for_async_insert} defaults to 1, so this was
     * never a hole in the CHECK-barrier contract; what differed was coalescing, and with it the
     * atomicity of a refused insert that #548's design rests on.
     *
     * <p>Asserted against the server's own record of the query rather than against the config,
     * because the config was never the thing in doubt. This is also the test the rest of this class
     * leans on: every other test here sets the flag off and asserts read-after-write.</p>
     */
    @Test
    void turningAsyncInsertsOffSendsTheSettingRatherThanStayingSilent() throws Exception {
        assumeServerDefaultsAsyncInsertOn();
        persistOneRowInto("async_off_probe", false);

        Assertions.assertThat(settingRecordedAsChanged("async_off_probe", "async_insert"))
                .as("off must send async_insert=0; empty means nothing was sent and the server"
                        + " default (coalescing) applied")
                .isEqualTo("0");
    }

    /**
     * And turning it on still sends the pair it always did.
     *
     * <p>The mirror matters: without it the test above passes for a build that hardcodes
     * {@code async_insert=0} and ignores the flag entirely. Such a build sends no
     * {@code wait_for_async_insert} at all, so the first assertion reads empty and fails.</p>
     *
     * <p>The second assertion closes the other gap: a build that keeps the wait at 0 but sends
     * {@code async_insert=0} on the on branch too. On this image "sent 1" and "sent nothing" both
     * record empty, so empty is the only value the on branch may produce; {@code "0"} is the one it
     * must not.</p>
     */
    @Test
    void turningAsyncInsertsOnStillSendsCoalescingAndSkipsTheWait() throws Exception {
        assumeServerDefaultsAsyncInsertOn();
        persistOneRowInto("async_on_probe", true);

        // wait_for_async_insert differs from the default and is what the coalescing path is
        // actually for.
        Assertions.assertThat(settingRecordedAsChanged("async_on_probe", "wait_for_async_insert"))
                .as("the coalescing path must still skip the wait, or turning the flag on buys"
                        + " nothing it is documented to buy")
                .isEqualTo("0");
        Assertions.assertThat(settingRecordedAsChanged("async_on_probe", "async_insert"))
                .as("1 is the server default, so the on path must record nothing here;"
                        + " \"0\" means the on branch turned coalescing off")
                .isEmpty();
    }
}
