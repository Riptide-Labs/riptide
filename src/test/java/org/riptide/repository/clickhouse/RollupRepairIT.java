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
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
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
        admin.execute("DROP VIEW IF EXISTS " + FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP)).get();
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
        try (var records = admin.queryRecords(sql + "").get()) {
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
}
