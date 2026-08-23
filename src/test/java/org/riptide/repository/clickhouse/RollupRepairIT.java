/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.provisioning.ProvisioningDdl;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * In-place rollup repair against a real server (#470).
 *
 * <p>The scenario every test here builds is the one that cannot be reached any other way: a rollup
 * created by an <em>older</em> version, which {@code CREATE … IF NOT EXISTS} then no-ops over
 * forever. It is simulated by creating {@code flows_by_application_1m} without its last dimension
 * and letting the repair discover it, which is exactly what an upgrade does.</p>
 */
@Testcontainers
public class RollupRepairIT {

    private static final String DATABASE = "repair";
    private static final String ROLLUP = FlowsSchema.ROLLUP_BY_APPLICATION;
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static String endpoint;
    private static Client admin;

    @BeforeAll
    static void bootstrap() throws Exception {
        endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        admin = new Client.Builder().addEndpoint(endpoint)
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase("default").build();
        admin.execute(FlowsSchema.createDatabase(DATABASE)).get();
        admin.execute(FlowsSchema.createFlowsTable(DATABASE)).get();
    }

    /**
     * Every test starts from an empty database. They share a container and mutate the same rollup,
     * and JUnit does not promise an order — without this, one test's leftover column decides
     * another's outcome.
     */
    @BeforeEach
    void clean() throws Exception {
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            admin.execute("DROP VIEW IF EXISTS " + FlowsSchema.qualifiedRollupView(DATABASE, rollup)).get();
            admin.execute("DROP TABLE IF EXISTS " + FlowsSchema.qualifiedRollup(DATABASE, rollup)).get();
        }
        admin.execute("TRUNCATE TABLE IF EXISTS " + FlowsSchema.qualifiedFlows(DATABASE)).get();
    }

    private static ClickhouseConfig config(final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint);
        config.setDatabase(DATABASE);
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setManageSchema(manageSchema);
        config.setAsyncInserts(false);
        return config;
    }

    /** Manage mode: starting this is what performs the repair. */
    private static ClickhouseRepository repository() {
        return new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config(true), RESOLVERS);
    }

    /**
     * A started repository that inserts but never repairs, so the writer in these tests does not
     * perform the very change it is meant to observe. Validate mode also registers the POJO, which
     * {@code persist} needs and which only {@code start()} does.
     */
    private static ClickhouseRepository startedWriter() {
        final var writer = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config(false), RESOLVERS);
        writer.start();
        return writer;
    }

    /** A rollup as an older riptide would have left it: one dimension short, view to match. */
    private static void createRollupMissingItsLastDimension() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        admin.execute("CREATE TABLE " + target + " ("
                + "tenant String, organisation String, timestamp DateTime('UTC'), zone String,"
                + " application LowCardinality(String),"
                + " bytes UInt64, packets UInt64, flowCount UInt64,"
                + " bytesIn UInt64, bytesOut UInt64, packetsIn UInt64, packetsOut UInt64)"
                + " ENGINE = SummingMergeTree()"
                + " PRIMARY KEY (tenant, organisation, timestamp, zone, application)"
                + " ORDER BY (tenant, organisation, timestamp, zone, application)"
                + " PARTITION BY toYYYYMM(timestamp)").get();
        admin.execute("CREATE MATERIALIZED VIEW " + view + " TO " + target + " AS SELECT"
                + " f.tenant AS tenant, f.organisation AS organisation,"
                + " toStartOfMinute(f.timestamp) AS timestamp, f.zone AS zone,"
                + " ifNull(f.application, '') AS application,"
                + " sum(f.bytes) AS bytes, sum(f.packets) AS packets, count() AS flowCount,"
                + " sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn,"
                + " sumIf(f.bytes, f.direction = 'EGRESS') AS bytesOut,"
                + " sumIf(f.packets, f.direction = 'INGRESS') AS packetsIn,"
                + " sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut"
                + " FROM " + FlowsSchema.qualifiedFlows(DATABASE) + " AS f"
                + " GROUP BY tenant, organisation, timestamp, zone, application").get();
    }

    private static long scalar(final String sql) throws Exception {
        try (var records = admin.queryRecords(sql).get()) {
            for (final var record : records) {
                return record.getLong("v");
            }
        }
        return -1;
    }

    private static String sortKeyOf(final String table) throws Exception {
        try (var records = admin.queryRecords("SELECT sorting_key AS k FROM system.tables"
                + " WHERE database = '" + DATABASE + "' AND name = '" + table + "'").get()) {
            for (final var record : records) {
                return record.getString("k");
            }
        }
        return null;
    }

    /**
     * A repair applied mid-stream loses no aggregation.
     *
     * <p>Flows are inserted continuously while the repair runs, so the swap lands between writes. A
     * materialized view does not backfill, so any window in which no view is attached is a permanent
     * hole in the rollup, and the count has to come out exact.</p>
     *
     * <p><b>What this does NOT pin, established by mutation.</b> Replacing {@code MODIFY QUERY} with
     * {@code DROP} + {@code CREATE} — the alternative design this change rejects — leaves this test
     * green. The two statements run back-to-back in under a millisecond, and at this test's write
     * cadence nothing lands in the gap. The 0.44% loss recorded in {@code design.md} came from 900
     * rows of continuous single-row inserts; reproducing that sensitivity here would cost seconds of
     * CI for a property already measured. The mechanism is pinned instead by
     * {@code FlowsSchemaTest.theRepairedViewSelectsExactlyWhatAFreshOneDoes}, which asserts the
     * emitted statement verbatim.</p>
     */
    @Test
    void aRepairMidStreamLosesNoAggregation() throws Exception {
        createRollupMissingItsLastDimension();
        final var repo = startedWriter();
        final var stop = new AtomicBoolean();
        final var inserted = new AtomicInteger();

        final Thread writer = new Thread(() -> {
            while (!stop.get()) {
                try {
                    final var batch = new java.util.ArrayList<org.riptide.pipeline.EnrichedFlow>();
                    for (int i = 0; i < 10; i++) {
                        batch.add(flow("acme", "acme-eu", 10000 + (inserted.get() + i) % 50));
                    }
                    repo.persist(batch);
                    inserted.addAndGet(batch.size());
                } catch (final Exception e) {
                    return;
                }
            }
        });

        writer.start();
        Thread.sleep(1_500);
        repository().start();          // the repair happens here, mid-stream
        Thread.sleep(1_500);
        stop.set(true);
        writer.join(10_000);

        assertThat(sortKeyOf(ROLLUP))
                .as("the repair appended the missing dimension")
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));

        final long raw = scalar("SELECT count() AS v FROM " + FlowsSchema.qualifiedFlows(DATABASE));
        final long aggregated = scalar("SELECT sum(flowCount) AS v FROM "
                + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP));
        assertThat(aggregated)
                .as("every flow inserted during the repair must still be aggregated")
                .isEqualTo(raw);
        assertThat(raw)
                .as("enough traffic either side of the swap for 'mid-stream' to mean anything")
                .isGreaterThan(200);
    }

    /**
     * The boundary an appended dimension gets for free: rows aggregated before it existed read the
     * type default, and only those. Without it a query spanning the upgrade cannot tell which rows
     * predate the change.
     */
    @Test
    void rowsAggregatedBeforeTheAppendReadTheTypeDefault() throws Exception {
        createRollupMissingItsLastDimension();
        final var repo = startedWriter();
        repo.persist(List.of(flow("before", "org", 1111)));
        Thread.sleep(300);

        repository().start();

        repo.persist(List.of(flow("after", "org", 2222)));
        Thread.sleep(300);

        assertThat(scalar("SELECT count() AS v FROM " + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)
                + " WHERE tenant = 'before' AND protocol = 0"))
                .as("pre-append rows carry the type default, which is what marks them")
                .isPositive();
        assertThat(scalar("SELECT count() AS v FROM " + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)
                + " WHERE tenant = 'after' AND protocol != 0"))
                .as("rows aggregated after the append carry the real value")
                .isPositive();
    }

    /** The repair is unconditional, so a second start must change nothing and say nothing. */
    @Test
    void aSecondStartIsANoOp() throws Exception {
        createRollupMissingItsLastDimension();
        repository().start();
        final String afterFirst = sortKeyOf(ROLLUP);

        repository().start();

        assertThat(sortKeyOf(ROLLUP))
                .as("re-running an idempotent repair must not disturb the schema")
                .isEqualTo(afterFirst)
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));
    }

    /**
     * #571 froze the primary key, which made a sorting-key shrink legal where ClickHouse's prefix
     * rule used to reject it. A reverted dimension would then change a rollup's grain silently, so
     * the repair refuses rather than applying it.
     */
    @Test
    void aSortKeyThatWouldShrinkIsRefused() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        repository().start();                                   // creates it at the current shape
        admin.execute("ALTER TABLE " + target
                + " ADD COLUMN IF NOT EXISTS srcCity LowCardinality(String),"
                + " MODIFY ORDER BY (" + FlowsSchema.rollupSortKeys().get(ROLLUP) + ", srcCity)").get();
        final String longer = sortKeyOf(ROLLUP);

        repository().start();                                   // intended key is now shorter

        assertThat(sortKeyOf(ROLLUP))
                .as("a shrink is not an append; the rollup keeps the grain it has")
                .isEqualTo(longer);
    }

    /**
     * A corrected aggregate is deliberately NOT repaired.
     *
     * <p>{@code MODIFY QUERY} could fix it forward in one statement, which is exactly why this needs
     * pinning: the restraint is invisible in the code. Repairing would readmit a rollup whose
     * earlier rows were computed the old way, with no column distinguishing them — worse than
     * #572's declined rollup, which at least answers correctly from raw {@code flows}. The repair
     * only runs when the sorting key or the column set differs, and a corrected expression changes
     * neither.</p>
     */
    @Test
    void aCorrectedExpressionIsNotRepaired() throws Exception {
        repository().start();                                   // rollups at the current shape
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        final String wrong = FlowsSchema.rollupSelects(DATABASE).get(ROLLUP)
                .replace("sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut",
                        "sum(f.packets) AS packetsOut");
        admin.execute("ALTER TABLE " + view + " MODIFY QUERY " + wrong).get();
        final String before = selectOf(ROLLUP + "_mv");

        repository().start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("riptide must leave a differently-computed measure alone, not silently fix it forward")
                .isEqualTo(before);
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("and the query path declines it instead")
                .isFalse();
    }

    private static String selectOf(final String view) throws Exception {
        try (var records = admin.queryRecords("SELECT as_select AS s FROM system.tables"
                + " WHERE database = '" + DATABASE + "' AND name = '" + view + "'").get()) {
            for (final var record : records) {
                return record.getString("s");
            }
        }
        return null;
    }

    /**
     * The {@code onboard} repair, executed rather than string-asserted (#470).
     *
     * <p>A provisioned deployment's collector runs in validate mode and issues no DDL, so
     * {@code riptide onboard} is the only path it has to a current rollup shape. Its statements are
     * generated without reading the live schema, which makes running them against a real server the
     * only way to know they apply — and that they no-op when the rollup is already current.</p>
     */
    @Test
    void theOnboardRepairAppliesAndThenNoOps() throws Exception {
        createRollupMissingItsLastDimension();

        for (final String ddl : ProvisioningDdl.repairRollups(DATABASE, List.of(ROLLUP), Set.of(ROLLUP))) {
            admin.execute(ddl).get();
        }
        assertThat(sortKeyOf(ROLLUP))
                .as("onboard brings an existing rollup up to this version's shape")
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));

        for (final String ddl : ProvisioningDdl.repairRollups(DATABASE, List.of(ROLLUP), Set.of(ROLLUP))) {
            admin.execute(ddl).get();
        }
        assertThat(sortKeyOf(ROLLUP))
                .as("and re-running onboard changes nothing")
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));
    }

    /**
     * The guard that stops onboard shrinking a key. ClickHouse does <b>not</b> reject this once
     * #571 froze the primary key — verified — so an unguarded emission would change a rollup's
     * grain in place with no error.
     */
    @Test
    void theServerDoesNotRejectAShrinkSoThePlannerMust() throws Exception {
        repository().start();
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        admin.execute("ALTER TABLE " + target + " ADD COLUMN IF NOT EXISTS srcCity LowCardinality(String),"
                + " MODIFY ORDER BY (" + FlowsSchema.rollupSortKeys().get(ROLLUP) + ", srcCity)").get();

        // the server accepts the shrink, which is why the planner has to refuse it
        admin.execute("ALTER TABLE " + target + " MODIFY ORDER BY ("
                + FlowsSchema.rollupSortKeys().get(ROLLUP) + ")").get();
        assertThat(sortKeyOf(ROLLUP))
                .as("ClickHouse allowed the shrink; nothing but riptide stands between it and the data")
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));

        assertThat(FlowsSchema.planRollupRepair(
                        Map.of(ROLLUP, FlowsSchema.rollupSortKeys().get(ROLLUP) + ", srcCity"),
                        Map.of(ROLLUP, Set.of("srcCity")))
                .refused())
                .containsKey(ROLLUP);
    }

    /**
     * A view stranded by a half-applied repair is picked up on the next start.
     *
     * <p>If a target {@code ALTER} lands and anything before its {@code MODIFY QUERY} throws — a
     * later rollup's ALTER, a dropped connection — the target is already current when the process
     * restarts. Planning view repair from the target's shape would therefore skip it, and the view
     * would keep the old SELECT forever: the appended dimension would read its type default for
     * every future row, with drift reported but never healed.</p>
     */
    @Test
    void aViewLeftBehindByAHalfAppliedRepairIsHealedOnTheNextStart() throws Exception {
        createRollupMissingItsLastDimension();
        // exactly the half-applied state: target altered, view untouched
        admin.execute(FlowsSchema.alterRollupTargets(DATABASE).get(ROLLUP)).get();
        assertThat(sortKeyOf(ROLLUP)).isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));
        assertThat(selectOf(ROLLUP + "_mv")).doesNotContain("protocol");

        repository().start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("the view must not be stranded just because its target already looks current")
                .contains("protocol");
    }

    /**
     * The point of carrying the rate at all: {@code SUM(bytes * samplingInterval)} means the same
     * thing against a rollup as against raw {@code flows}. Before this, sampling-corrected volume
     * was unanswerable beyond the raw table's retention, because the rollups — the only thing that
     * survives that long — did not carry the rate.
     *
     * <p>At mixed rates, deliberately. Every fixture flow carries {@code samplingInterval = 1.0}, so
     * an earlier version of this test reduced both sides to {@code sum(bytes)} and passed whatever
     * the view had written — a constant, the wrong column, or nothing resembling a rate at all. The
     * unscaled total is asserted to differ, which is what makes the equality mean something.</p>
     */
    @Test
    void theScaledSumIsIdenticalAgainstRawAndRollup() throws Exception {
        repository().start();
        final var repo = startedWriter();
        final double[] rates = {1.0d, 64.0d, 1000.0d};
        for (int i = 0; i < 20; i++) {
            final var flow = flow("scaled", "org", 3000 + i);
            flow.setSamplingInterval(rates[i % rates.length]);
            repo.persist(List.of(flow));
        }
        Thread.sleep(500);

        final String rollupTable = FlowsSchema.qualifiedRollup(DATABASE, FlowsSchema.ROLLUP_BY_CONVERSATION);
        final long raw = scalar("SELECT toUInt64(sum(bytes * samplingInterval)) AS v FROM "
                + FlowsSchema.qualifiedFlows(DATABASE) + " WHERE tenant = 'scaled'");
        final long rollup = scalar("SELECT toUInt64(sum(bytes * samplingInterval)) AS v FROM "
                + rollupTable + " WHERE tenant = 'scaled'");
        final long unscaled = scalar("SELECT toUInt64(sum(bytes)) AS v FROM "
                + rollupTable + " WHERE tenant = 'scaled'");

        assertThat(rollup).isEqualTo(raw).isPositive();
        assertThat(rollup)
                .as("the rates have to be doing something, or this test cannot fail")
                .isNotEqualTo(unscaled);
    }

    /**
     * The boundary, end to end. Rows aggregated before the rate was appended read {@code 0} — the
     * type default, and the only marker a sort-key column can have — so
     * {@code WHERE samplingInterval > 0} selects exactly the rows aggregated after.
     */
    @Test
    void theRateBoundaryIsAPredicateNotARememberedDate() throws Exception {
        createRollupMissingItsLastDimension();
        final var repo = startedWriter();
        repo.persist(List.of(flow("beforeAppend", "org", 4001)));
        Thread.sleep(400);

        repository().start();                                   // appends samplingInterval

        repo.persist(List.of(flow("afterAppend", "org", 4002)));
        Thread.sleep(400);

        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'beforeAppend' AND samplingInterval = 0"))
                .as("rows aggregated before the append carry the reserved value")
                .isPositive();
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'beforeAppend' AND samplingInterval > 0"))
                .as("and are excluded by the boundary predicate")
                .isZero();
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'afterAppend' AND samplingInterval > 0"))
                .as("rows aggregated after carry a real rate, which is never 0")
                .isPositive();
    }

    /**
     * The case that motivates measuring sort-key growth at all: two exporters sampling at different
     * rates, feeding one geo/ASN group.
     *
     * <p>They must produce two rollup rows, not one — collapsing them would average away the rates
     * and make the correction meaningless. The growth this costs was measured before the change at
     * a median of 1.0214 across the four rollups, which is what the decision rule was evaluated
     * against.</p>
     */
    @Test
    void twoExportersAtDifferentRatesSplitOneGroupAndStillSumCorrectly() throws Exception {
        repository().start();
        final var repo = startedWriter();

        repo.persist(List.of(
                sampledAt(1.0d, "203.0.113.7"),
                sampledAt(100.0d, "203.0.113.8")));
        Thread.sleep(600);

        final String target = FlowsSchema.qualifiedRollup(DATABASE, FlowsSchema.ROLLUP_BY_GEO_ASN);
        assertThat(scalar("SELECT count() AS v FROM " + target + " WHERE tenant = 'rates'"))
                .as("one geo/ASN group, two rates, two rows — collapsing them would lose the rates")
                .isEqualTo(2);
        assertThat(scalar("SELECT toUInt64(sum(bytes * samplingInterval)) AS v FROM "
                + target + " WHERE tenant = 'rates'"))
                .as("1234*1 + 1234*100")
                .isEqualTo(1234L + 1234L * 100L);
    }

    /** Same geo/ASN dimensions, different exporter and rate. */
    private static org.riptide.pipeline.EnrichedFlow sampledAt(final double rate, final String exporter)
            throws Exception {
        final var base = flow("rates", "org", 5000);
        base.setSamplingInterval(rate);
        base.setExporterAddr(exporter);
        return base;
    }
}
