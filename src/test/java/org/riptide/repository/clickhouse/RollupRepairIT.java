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
import org.riptide.flows.parser.data.Flow;
import org.riptide.provisioning.ProvisioningDdl;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;
import org.riptide.schema.RollupShapeCheck;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            // So one test can create a deliberately under-privileged user and watch a DDL statement
            // actually fail. Without a real ACCESS_DENIED there is no way to reach the "repair could
            // not complete" path, which is why it went untested for three rounds.
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
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

    /**
     * A rollup whose DDL fails is kept out of the query path, and does not stop the collector.
     *
     * <p>Both halves need a statement that genuinely fails, which needs a user that genuinely lacks
     * a privilege — the reason this sat untested through three review rounds. The user here may
     * create tables but not views, so the four targets are created at this version's shape and none
     * of their views can be.</p>
     *
     * <p>That combination is precisely the one the shape check does <em>not</em> catch: columns
     * match, the view is invisible, and the verdict is {@code UNVERIFIABLE}, which is deliberately
     * not declined ("an unverified rollup is not a known-bad one"). So the only thing standing
     * between an empty rollup and every long-range query is the repair recording what it could not
     * do. Deleting that seed leaves every other rollup test green.</p>
     */
    @Test
    void aRollupWhoseDdlFailedIsDeclinedAndStartupSurvivesIt() throws Exception {
        admin.execute("DROP USER IF EXISTS noviews").get();
        admin.execute("CREATE USER noviews IDENTIFIED WITH plaintext_password BY 'noviews'").get();
        // Everything manage mode needs, then the creation privilege revoked on the four _mv names
        // alone. Revoked rather than withheld because a materialized view with a TO clause is
        // authorised as a TABLE, not as a VIEW — granting CREATE TABLE for the targets grants it for
        // their views too, which is how the first attempt at this test passed while proving nothing.
        admin.execute("GRANT SELECT, INSERT, CREATE DATABASE, CREATE TABLE, DROP TABLE, CREATE VIEW,"
                + " DROP VIEW, ALTER, SHOW ON " + DATABASE + ".* TO noviews").get();
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            admin.execute("REVOKE CREATE TABLE, CREATE VIEW ON " + DATABASE + "." + rollup
                    + "_mv FROM noviews").get();
        }

        final var config = config(true);
        config.setUsername(SecretRef.of("noviews"));
        config.setPassword(SecretRef.of("noviews"));
        final var collector = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);

        collector.start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("the view really could not be created — otherwise this test proves nothing")
                .isNull();
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("a rollup nothing is writing to must not answer queries, even though its target's"
                        + " columns match and the shape check therefore calls it UNVERIFIABLE")
                .isFalse();
    }

    /**
     * A rollup the check independently verifies as correct stays usable, even if a statement failed.
     *
     * <p>The counterweight to the test above. Every rollup DDL is a {@code CREATE … IF NOT EXISTS}
     * or an idempotent {@code ALTER}, so on a healthy deployment most of them no-op — and a
     * connection reset, a lock timeout or a missing grant on a statement that would have changed
     * nothing must not cost a correct rollup its place in the query path until the next restart.
     * {@code MATCHES} compares the target's columns and the view's stored SELECT against this
     * version, which is a stronger statement than any inference from which statement failed.</p>
     */
    @Test
    void aRollupThatVerifiesCleanIsNotDeclinedByAFailedNoOp() throws Exception {
        repository().start();                                   // everything correct, as admin
        admin.execute("DROP USER IF EXISTS noalter").get();
        admin.execute("CREATE USER noalter IDENTIFIED WITH plaintext_password BY 'noalter'").get();
        admin.execute("GRANT SELECT, INSERT, CREATE DATABASE, CREATE TABLE, DROP TABLE, CREATE VIEW,"
                + " DROP VIEW, ALTER, SHOW ON " + DATABASE + ".* TO noalter").get();
        // The CREATE for this one view will be denied — and it is a statement that would have
        // no-oped, because the view is already there and already right.
        admin.execute("REVOKE CREATE TABLE, CREATE VIEW ON " + DATABASE + "." + ROLLUP
                + "_mv FROM noalter").get();

        final var config = config(true);
        config.setUsername(SecretRef.of("noalter"));
        config.setPassword(SecretRef.of("noalter"));
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS).start();

        assertThat(deniedCreateOf(ROLLUP + "_mv"))
                .as("the CREATE must really have been denied, or this test passes vacuously: with"
                        + " nothing recorded as unrepaired there is no clear for MATCHES to perform")
                .isTrue();
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("a rollup whose shape verifies clean must keep answering queries — a failed"
                        + " no-op is not evidence of anything")
                .isTrue();
    }

    /**
     * A rollup the planner refused is kept out of the query path, and its view is left alone.
     *
     * <p>The state is the one the Code 36 refusal was added for: an operator has hand-added
     * {@code samplingInterval} to the target after reading that riptide appends it, so the column
     * exists but is not in the sorting key and no {@code ALTER} can put it there.</p>
     *
     * <p>Refusing the target is not enough on its own, and this is the trap. The view's
     * {@code CREATE … IF NOT EXISTS} <em>succeeds</em> — the target does have the column it names —
     * so nothing marks the rollup as unrepaired, and the view repair then re-points it at a SELECT
     * that writes the rate into a non-key numeric column of a {@code SummingMergeTree}. ClickHouse
     * sums every numeric column outside the sorting key, so the rate itself would accumulate across
     * merges: {@code sum(bytes * samplingInterval)} inflated by an arbitrary factor, and
     * {@code samplingInterval > 0} no longer meaning what it says. The shape check cannot catch it
     * either — it compares columns and the view's SELECT, never the sorting key — so it reports
     * MATCHES and the rollup keeps answering.</p>
     *
     * <p>Before this change the same state failed loudly with Code 36 on every start. A refusal that
     * converts a loud failure into a quiet wrong number is worse than no refusal.</p>
     */
    @Test
    void aRefusedRollupIsDeclinedAndItsViewIsNotRepointed() throws Exception {
        createRollupWithTheAppendedDimensionsOutsideItsSortingKey();
        final String before = selectOf(ROLLUP + "_mv");
        assertThat(RollupShapeCheck.normalise(before))
                .as("the fixture must present the state the shape check would otherwise call MATCHES:"
                        + " every column present, this version's SELECT, and only the key wrong")
                .isEqualTo(RollupShapeCheck.normalise(FlowsSchema.rollupSelects(DATABASE).get(ROLLUP)));

        repository().start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("re-pointing the view would start summing the sampling rate itself")
                .isEqualTo(before);
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("a rollup riptide has refused to repair must not keep answering queries")
                .isFalse();
    }

    /**
     * riptide must not BUILD the view it has just refused to repair.
     *
     * <p>The sibling of the test above, with the view absent — and the case that actually bites,
     * because {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} <em>succeeds</em> here: a target
     * carrying the rate outside its sorting key has every column the SELECT names. So riptide would
     * create a view writing the rate into a non-key numeric column of a {@code SummingMergeTree},
     * which the engine then sums across merges. Declining the rollup does not undo that: the rows
     * are wrong, durable, and outlive the process that declined them.</p>
     */
    @Test
    void aRefusedRollupGetsNoViewBuiltForIt() throws Exception {
        createRollupWithTheAppendedDimensionsOutsideItsSortingKey();
        admin.execute("DROP VIEW " + FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP)).get();

        repository().start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("building the writer for a rollup just refused is two halves of opposite"
                        + " decisions; the rows it would write are wrong and durable")
                .isNull();
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("and it stays out of the query path")
                .isFalse();
    }

    /**
     * The hand-added-column state: both appended dimensions are columns of the target, but neither
     * is part of its key, so the refusal it drives reports them together.
     *
     * <p>Every dimension this version declares has to be present as a column, or the shape check
     * reports the target as missing one and the test passes for that reason instead of the refusal.
     * A dimension appended to {@code FlowsSchema} therefore has to be added here too — leaving it out
     * fails loudly (the view cannot be created at all), which is the good failure mode.</p>
     */
    private static void createRollupWithTheAppendedDimensionsOutsideItsSortingKey() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        admin.execute("CREATE TABLE " + target + " ("
                + "tenant String, organisation String, timestamp DateTime('UTC'), zone String,"
                + " application LowCardinality(String), protocol UInt8,"
                + " samplingInterval Float64, flowProtocol LowCardinality(String),"
                + " bytes UInt64, packets UInt64, flowCount UInt64,"
                + " bytesIn UInt64, bytesOut UInt64, packetsIn UInt64, packetsOut UInt64,"
                // Present for the same reason as every other column here: this fixture's point
                // is that the sorting key is the ONLY thing wrong, so a missing measure would
                // fail the view's CREATE with THERE_IS_NO_COLUMN and the refusal path this
                // test exists for would never run.
                + " samplingProvenanceMask SimpleAggregateFunction(groupBitOr, UInt8))"
                + " ENGINE = SummingMergeTree()"
                + " PRIMARY KEY (tenant, organisation, timestamp, zone, application, protocol)"
                + " ORDER BY (tenant, organisation, timestamp, zone, application, protocol)"
                + " PARTITION BY toYYYYMM(timestamp)").get();
        // THIS VERSION'S SELECT, deliberately. An earlier draft gave the view a stale SELECT, which
        // the shape check independently reports as DRIFTED — so the test passed without the refusal
        // path ever being the reason, and deleting the decline left the whole suite green. With the
        // current SELECT and every column present, columns and view both compare clean and the
        // sorting key is the only thing wrong: exactly the state the refusal exists for.
        admin.execute("CREATE MATERIALIZED VIEW " + view + " TO " + target + " AS "
                + FlowsSchema.rollupSelects(DATABASE).get(ROLLUP)).get();
    }

    /**
     * A rollup missing a measure is refused with a reason and a remedy, not repaired and not
     * left to fail (#654).
     *
     * <p>The rebuild is the only remedy: a measure added in place reads {@code 0} for every row
     * aggregated before the upgrade. Before #654 the planner skipped the rollup instead of refusing
     * it, so {@code planViewCreation} still included it and the {@code CREATE MATERIALIZED VIEW IF
     * NOT EXISTS} failed with {@code THERE_IS_NO_COLUMN} on every start, ending "until it is
     * repaired". Now the planner's own "left as it is" line is the operator's first word, the
     * CREATE is not attempted, and nothing promises a repair.</p>
     *
     * <p>{@code RollupShapeDriftIT} covers the drift line in validate mode, where the planner never
     * runs. Both are needed: that one cannot reach the planner at all, and this one is the only
     * place the refusal is emitted.</p>
     */
    @Test
    void aManageModeStartOnARollupMissingAMeasureIsRefusedWithARemedy() throws Exception {
        createRollupMissingAMeasure();

        final List<String> logged = RollupShapeDriftIT.messages(
                RollupShapeDriftIT.captureRepositoryLog(() -> repository().start()));

        assertThat(logged)
                .as("the refusal names the rollup, the missing measure, the reason and the remedy")
                .anyMatch(m -> m.contains("Rollup " + ROLLUP + " left as it is")
                        && m.contains("packetsOut")
                        && m.contains("cannot be added in place")
                        && m.contains("Drop the rollup's view and target table"));
        assertThat(logged)
                .as("the view's CREATE is not attempted against a target it would fail on")
                .noneMatch(m -> m.contains("THERE_IS_NO_COLUMN"));
        // Checked on riptide's own lines only. Server text is echoed into the failure lines, and
        // this database is literally named "repair", so a loose match over every line could trip
        // on a DDL echo rather than on a promise.
        assertThat(logged.stream().filter(m -> m.startsWith("Rollup ")).toList())
                .as("no line may tell the operator a repair is coming, in the phrasings pinned by"
                        + " PROMISES_A_REPAIR")
                .noneMatch(m -> PROMISES_A_REPAIR.matcher(m).find());
        assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, ROLLUP)))
                .as("and the rollup this message is about is genuinely out of the query path")
                .isFalse();
    }

    /**
     * Wordings that tell an operator a repair is on its way.
     *
     * <p>Matched as a claim rather than as one sentence, because the defect #654 fixed was prose and
     * prose gets reworded. Pinned phrasings, not "any wording": "until (it is) repaired", "will be
     * repaired", "repair is deferred", "repairs itself", "repaired on the next start". Shared with
     * {@code RollupShapeDriftIT} so the two cannot drift apart.</p>
     */
    static final Pattern PROMISES_A_REPAIR = Pattern.compile(
            "until (it is |it has been )?repaired|will be repaired|repair is deferred"
                    + "|repairs (itself|themselves)|repaired on the next start",
            Pattern.CASE_INSENSITIVE);

    /** A rollup target one measure short, with a view to match — the shape an upgrade leaves behind. */
    private static void createRollupMissingAMeasure() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        final String dropped = "packetsOut";
        final String columns = FlowsSchema.rollupColumns().get(ROLLUP).entrySet().stream()
                .filter(column -> !dropped.equals(column.getKey()))
                .map(column -> column.getKey() + " " + column.getValue())
                .collect(Collectors.joining(", "));
        final String key = FlowsSchema.rollupSortKeys().get(ROLLUP);
        admin.execute("CREATE TABLE " + target + " (" + columns + ")"
                + " ENGINE = SummingMergeTree() PRIMARY KEY (" + key + ") ORDER BY (" + key + ")"
                + " PARTITION BY toYYYYMM(timestamp)").get();
        // The view as the older version wrote it, so it creates cleanly here. The measure's own
        // expression contains a comma, so the whole projection is removed as one literal, with its
        // leading separator; the guards below catch a reformatted SELECT.
        final String full = FlowsSchema.rollupSelects(DATABASE).get(ROLLUP);
        final String shortened = full.replace(
                ",\n    sumIf(f.packets, f.direction = 'EGRESS') AS " + dropped, "");
        assertThat(shortened)
                .as("the %s projection must be found and removed, or the fixture builds the full"
                        + " view and the assertions above pass for the wrong reason", dropped)
                .isNotEqualTo(full)
                .doesNotContain(dropped);
        admin.execute("CREATE MATERIALIZED VIEW " + view + " TO " + target + " AS " + shortened).get();
    }

    /** A rollup as a pre-v0.11 riptide would have left it: three dimensions short, view to match. */
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

    /**
     * The rollup exactly as the release before #581 left it: every column but the provenance
     * summary, the sorting key already at this version's, and a view to match. This is the state
     * every up-to-date deployment is in when it upgrades, and the one the dimension fixtures above
     * cannot produce: the repair must add the measure while changing nothing else.
     */
    private static void createRollupMissingOnlyTheProvenanceSummary() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        final String dropped = "samplingProvenanceMask";
        final String columns = FlowsSchema.rollupColumns().get(ROLLUP).entrySet().stream()
                .filter(column -> !dropped.equals(column.getKey()))
                .map(column -> column.getKey() + " " + column.getValue())
                .collect(Collectors.joining(", "));
        final String key = FlowsSchema.rollupSortKeys().get(ROLLUP);
        admin.execute("CREATE TABLE " + target + " (" + columns + ")"
                + " ENGINE = SummingMergeTree() PRIMARY KEY (" + key + ") ORDER BY (" + key + ")"
                + " PARTITION BY toYYYYMM(timestamp)").get();
        // The view as the older version wrote it. The mask projection is removed as one literal,
        // with its leading separator; the guards below catch a reformatted SELECT.
        final String marker = " AS " + dropped;
        final String full = FlowsSchema.rollupSelects(DATABASE).get(ROLLUP);
        final int from = full.indexOf(",\n    groupBitOr(");
        final int to = full.indexOf(marker);
        assertThat(from)
                .as("the %s projection must be found and removed, or the fixture builds the full"
                        + " view and the repair below has nothing to do", dropped)
                .isPositive();
        assertThat(to)
                .as("the ' AS %s' marker must close the projection the splice starts at, or the"
                        + " substring below cuts a nonsense span", dropped)
                .isGreaterThan(from);
        final String shortened = full.substring(0, from) + full.substring(to + marker.length());
        assertThat(shortened).isNotEqualTo(full).doesNotContain(dropped);
        admin.execute("CREATE MATERIALIZED VIEW " + view + " TO " + target + " AS " + shortened).get();
    }

    /**
     * A rollup missing only the provenance summary is repaired in place, end to end (#581).
     *
     * <p>The planning half is pinned by {@code FlowsSchemaTest}; this runs the statement it plans
     * against a real server, in the exact state every up-to-date deployment is in on upgrade: an
     * {@code ADD COLUMN} that must land after {@code packetsOut}, a {@code MODIFY ORDER BY} naming
     * the key the table already has, and the view's {@code MODIFY QUERY}. Rows aggregated before
     * the repair read {@code 0} — no provenance information — and rows after carry their rung's
     * bit, which is the entire meaning #581 assigns the column.</p>
     */
    @Test
    void aRollupMissingOnlyTheProvenanceSummaryIsRepairedEndToEnd() throws Exception {
        createRollupMissingOnlyTheProvenanceSummary();
        final var repo = startedWriter();
        repo.persist(List.of(flow("before", "org", 1111)));
        Thread.sleep(300);

        repository().start();          // the repair happens here

        final var after = flow("after", "org", 2222);
        after.setSamplingProvenance(Flow.SamplingProvenance.Record);
        repo.persist(List.of(after));
        Thread.sleep(300);

        assertThat(sortKeyOf(ROLLUP))
                .as("the sorting key was already this version's and must come out unchanged")
                .isEqualTo(FlowsSchema.rollupSortKeys().get(ROLLUP));
        final List<String> physical = physicalColumnsOf(ROLLUP);
        assertThat(physical)
                .as("the mask must land directly after packetsOut, where a fresh table declares it,"
                        + " or a positional backfill corrupts an upgraded target")
                .containsSequence("packetsOut", "samplingProvenanceMask");
        assertThat(columnTypeOf(ROLLUP, "samplingProvenanceMask"))
                .as("the appended column must carry the summary type on the server: a bare UInt8 in"
                        + " the right position would pass the order check above and then be summed"
                        + " on merge instead of OR-ed")
                .isEqualTo("SimpleAggregateFunction(groupBitOr, UInt8)");
        assertThat(scalar("SELECT count() AS v FROM "
                + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP) + " WHERE tenant = 'before'"))
                .as("the pre-repair flow must have been aggregated, or the 0 below is an aggregate"
                        + " over nothing rather than a statement about history")
                .isPositive();
        assertThat(scalar("SELECT groupBitOr(samplingProvenanceMask) AS v FROM "
                + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP) + " WHERE tenant = 'before'"))
                .as("rows aggregated before the repair read 0: no provenance information")
                .isZero();
        assertThat(scalar("SELECT groupBitOr(samplingProvenanceMask) AS v FROM "
                + FlowsSchema.qualifiedRollup(DATABASE, ROLLUP) + " WHERE tenant = 'after'"))
                .as("rows aggregated after the repair carry the record rung's bit")
                .isEqualTo(1L);

        repository().start();          // and this shape's second start must be a no-op too
        assertThat(physicalColumnsOf(ROLLUP))
                .as("re-running the idempotent repair must not disturb the physical order")
                .isEqualTo(physical);
    }

    private static String columnTypeOf(final String table, final String column) throws Exception {
        try (var records = admin.queryRecords("SELECT type FROM system.columns WHERE database = '"
                + DATABASE + "' AND table = '" + table + "' AND name = '" + column + "'").get()) {
            for (final var record : records) {
                return record.getString("type");
            }
        }
        throw new AssertionError(column + " is not a column of " + table);
    }

    private static List<String> physicalColumnsOf(final String table) throws Exception {
        final List<String> columns = new ArrayList<>();
        try (var records = admin.queryRecords("SELECT name FROM system.columns WHERE database = '"
                + DATABASE + "' AND table = '" + table + "' ORDER BY position").get()) {
            records.forEach(record -> columns.add(record.getString("name")));
        }
        return columns;
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

    /**
     * A view selecting a dimension this version does not know is a downgrade, and must be left
     * alone rather than re-pointed at the narrower SELECT.
     *
     * <p>This is the one direction that destroys data instead of withholding it. ClickHouse accepts
     * a {@code MODIFY QUERY} that drops an output column — it does not validate against the target —
     * so the repair would succeed silently and every row aggregated afterwards would take the
     * column's type default. For {@code samplingInterval} that default is {@code 0}, the value
     * reserved for rows predating the append, so live traffic would become permanently
     * indistinguishable from pre-append rows.</p>
     *
     * <p>Simulated by adding a column this version has never heard of, since the real trigger is a
     * future version's dimension seen by this one.</p>
     */
    @Test
    void aViewFromANewerVersionIsLeftAloneRatherThanNarrowed() throws Exception {
        repository().start();
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("ALTER TABLE " + target + " ADD COLUMN IF NOT EXISTS fromTheFuture UInt8").get();
        final String wider = FlowsSchema.rollupSelects(DATABASE).get(ROLLUP)
                .replace("SELECT", "SELECT toUInt8(0) AS fromTheFuture,");
        admin.execute("ALTER TABLE " + view + " MODIFY QUERY " + wider).get();
        final String before = selectOf(ROLLUP + "_mv");
        assertThat(before).contains("fromTheFuture");

        repository().start();

        assertThat(selectOf(ROLLUP + "_mv"))
                .as("taking a dimension away would write this version's defaults over rows that mean"
                        + " something else")
                .isEqualTo(before);
        assertThat(RollupAvailability.usable(target))
                .as("and the rollup is declined rather than quietly narrowed")
                .isFalse();
    }

    /** Whether the under-privileged user is genuinely refused the view CREATE it just attempted. */
    private static boolean deniedCreateOf(final String view) throws Exception {
        try (var records = admin.queryRecords("SELECT count() AS c FROM system.grants"
                + " WHERE user_name = 'noalter' AND access_type IN ('CREATE TABLE', 'CREATE VIEW')"
                + " AND table = '" + view + "' AND is_partial_revoke = 1").get()) {
            for (final var record : records) {
                return record.getLong("c") > 0;
            }
        }
        return false;
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

    /**
     * Sampling-corrected volume on a mixed-protocol deployment, which is the whole of #583.
     *
     * <p>Both flows carry the same rate, so the only thing that can keep them apart in the rollup is
     * {@code flowProtocol}. That is deliberate: with the protocol absent from the sort key they
     * collapse into one row holding 4196 bytes, and no expression over that row can recover the
     * corrected total, because the row no longer records which half was pre-scaled.</p>
     *
     * <p>The three candidate answers are all asserted, not just the right one. 55296 is correct;
     * 4245504 is what multiplying every row by its rate gives, the defect the issue reports; 51200 is
     * what {@code WHERE flowProtocol != 'SFLOW'} gives, which is not "corrected volume" but corrected
     * volume of the NetFlow/IPFIX subset, short by every sFlow byte received. A test asserting only
     * "not inflated" would pass the third.</p>
     */
    @Test
    void correctedVolumeCountsSflowOnceAndScalesEverythingElse() throws Exception {
        repository().start();
        final var repo = startedWriter();

        repo.persist(List.of(
                protocolAt(Flow.FlowProtocol.SFLOW, 4096L, 512.0d),   // pre-scaled at ingest
                protocolAt(Flow.FlowProtocol.IPFIX, 100L, 512.0d)));  // still to be scaled
        Thread.sleep(600);

        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String corrected = "toUInt64(sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval)))";

        // FINAL, not a bare count. SummingMergeTree collapses on merge, and merges are asynchronous:
        // with flowProtocol removed from the sort key these two rows still return count() = 2 until
        // a merge happens, so the un-FINAL assertion passes for the mutant whenever the writer split
        // them across insert blocks. Verified on 26.7 — two blocks read back 2 rows summing 4196.
        assertThat(scalar("SELECT count() AS v FROM " + target + " FINAL WHERE tenant = 'mixed'"))
                .as("one group, one rate, two protocols — collapsing them makes the correction"
                        + " unrecoverable, since the row would not say which half was pre-scaled")
                .isEqualTo(2);

        assertThat(scalar("SELECT " + corrected + " AS v FROM " + target + " WHERE tenant = 'mixed'"))
                .as("4096 sFlow bytes counted once, plus 100 IPFIX bytes at 512")
                .isEqualTo(4096L + 100L * 512L);

        // The SCALING EXPRESSION ports to raw flows; the rollup's boundary predicates do not, and
        // `flowProtocol != ''` fails there outright with UNKNOWN_ELEMENT_OF_ENUM because the source
        // column is an Enum8 with no such member. Asserting only the expression here is correct, but
        // the earlier wording claimed the whole query was identical — see the paired test below,
        // which pins the half that does not port.
        assertThat(scalar("SELECT " + corrected + " AS v FROM "
                + FlowsSchema.qualifiedFlows(DATABASE) + " WHERE tenant = 'mixed'"))
                .as("the scaling expression against raw flows agrees, with no boundary predicates")
                .isEqualTo(4096L + 100L * 512L);

        assertThat(scalar("SELECT toUInt64(sum(bytes * samplingInterval)) AS v FROM "
                + target + " WHERE tenant = 'mixed'"))
                .as("scaling every row by its rate inflates the sFlow half, which is #583")
                .isEqualTo(4096L * 512L + 100L * 512L);

        assertThat(scalar("SELECT toUInt64(sum(bytes * samplingInterval)) AS v FROM " + target
                + " WHERE tenant = 'mixed' AND flowProtocol NOT IN ('', 'SFLOW')"))
                .as("and excluding sFlow answers a different question, short by every sFlow byte")
                .isEqualTo(100L * 512L);

        assertThat(scalar("SELECT toUInt64(sum(bytes)) AS v FROM " + target
                + " WHERE tenant = 'mixed' AND flowProtocol = 'SFLOW'"))
                .as("volume is attributable to a protocol, which no rollup could answer before")
                .isEqualTo(4096L);
    }

    /**
     * The rollup's boundary predicate is not portable to raw {@code flows}, and saying it is would be
     * worse than saying nothing.
     *
     * <p>{@code flowProtocol} is a {@code LowCardinality(String)} on a rollup, where {@code ''} marks
     * a row aggregated before the append. On raw {@code flows} it is an {@code Enum8} whose every
     * member is a real protocol, so comparing against {@code ''} is not merely pointless — the server
     * refuses the query. The docs recommend two different {@code WHERE} clauses for the two tables,
     * and this is what stops that from silently becoming untrue.</p>
     */
    @Test
    void theRollupBoundaryPredicateIsRejectedByTheRawTable() throws Exception {
        repository().start();
        final var repo = startedWriter();
        repo.persist(List.of(protocolAt(Flow.FlowProtocol.IPFIX, 100L, 512.0d)));
        Thread.sleep(600);

        assertThatThrownBy(() -> scalar("SELECT count() AS v FROM " + FlowsSchema.qualifiedFlows(DATABASE)
                + " WHERE tenant = 'mixed' AND flowProtocol != ''"))
                .as("the rollup's boundary predicate must not be copied onto raw flows")
                .hasMessageContaining("UNKNOWN_ELEMENT_OF_ENUM");

        assertThat(scalar("SELECT count() AS v FROM " + FlowsSchema.qualifiedFlows(DATABASE)
                + " WHERE tenant = 'mixed'"))
                .as("while the same query without it is fine, so the failure is the predicate")
                .isEqualTo(1);
    }

    /**
     * The protocol is appended in place, and rows aggregated before it reserve {@code ''}.
     *
     * <p>Started from a rollup that already carries the rate, not from one carrying neither: that is
     * the shape a v0.11.0 deployment actually has, and it is the only state in which this append is
     * the one under test.</p>
     */
    @Test
    void theProtocolIsAppendedInPlaceAndMarksRowsThatPredateIt() throws Exception {
        createRollupCarryingTheRateButNotTheProtocol();
        final var repo = startedWriter();
        repo.persist(List.of(flow("beforeProtocol", "org", 4101)));
        Thread.sleep(400);

        repository().start();                                   // appends flowProtocol

        repo.persist(List.of(flow("afterProtocol", "org", 4102)));
        Thread.sleep(400);

        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'beforeProtocol' AND flowProtocol = ''"))
                .as("rows aggregated before the append carry the reserved value")
                .isEqualTo(1);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'afterProtocol' AND flowProtocol = 'IPFIX'"))
                .as("rows aggregated after carry the flow's protocol, which is never ''")
                .isEqualTo(1);
    }

    /**
     * The two boundaries compose, and the rate's alone is not enough.
     *
     * <p>The rate shipped in v0.11.0 and the protocol appends after it, so a band of rows exists with
     * a known rate and an unknown protocol. Those rows satisfy {@code '' != 'SFLOW'}, so a corrected
     * query filtering only on the rate readmits them and re-inflates any sFlow they hold. This pins
     * that the middle band is real and that only both predicates together exclude it.</p>
     */
    @Test
    void theRatePredicateAloneDoesNotExcludeRowsPredatingTheProtocol() throws Exception {
        createRollupCarryingTheRateButNotTheProtocol();
        final var repo = startedWriter();
        repo.persist(List.of(flow("middleBand", "org", 4201)));
        Thread.sleep(400);

        repository().start();                                   // appends flowProtocol

        repo.persist(List.of(flow("middleBand", "org", 4202)));
        Thread.sleep(400);

        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'middleBand' AND samplingInterval > 0"))
                .as("the rate predicate admits the middle band, because its rate is genuinely known")
                .isEqualTo(2);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'middleBand' AND samplingInterval > 0 AND flowProtocol != ''"))
                .as("only both predicates together select rows whose rate and protocol are both known")
                .isEqualTo(1);
        assertThat(scalar("SELECT count() AS v FROM " + target
                + " WHERE tenant = 'middleBand' AND flowProtocol != 'SFLOW'"))
                .as("and '' is not 'SFLOW', which is why the middle band cannot be left to that test")
                .isEqualTo(2);
    }

    /** One flow of a given protocol, byte count and rate, all else identical so only these split it. */
    private static org.riptide.pipeline.EnrichedFlow protocolAt(
            final Flow.FlowProtocol protocol, final long bytes, final double rate) throws Exception {
        final var base = flow("mixed", "org", 6000);
        base.setFlowProtocol(protocol);
        base.setBytes(bytes);
        base.setSamplingInterval(rate);
        return base;
    }

    /**
     * The shape a v0.11.0 deployment has: every dimension through {@code samplingInterval}, and no
     * {@code flowProtocol}. {@link #createRollupMissingItsLastDimension()} predates both appends and
     * would leave two repairs under test at once.
     */
    private static void createRollupCarryingTheRateButNotTheProtocol() throws Exception {
        final String target = FlowsSchema.qualifiedRollup(DATABASE, ROLLUP);
        final String view = FlowsSchema.qualifiedRollupView(DATABASE, ROLLUP);
        admin.execute("DROP VIEW IF EXISTS " + view).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        admin.execute("CREATE TABLE " + target + " ("
                + "tenant String, organisation String, timestamp DateTime('UTC'), zone String,"
                + " application LowCardinality(String), protocol UInt8, samplingInterval Float64,"
                + " bytes UInt64, packets UInt64, flowCount UInt64,"
                + " bytesIn UInt64, bytesOut UInt64, packetsIn UInt64, packetsOut UInt64)"
                + " ENGINE = SummingMergeTree()"
                + " PRIMARY KEY (tenant, organisation, timestamp, zone, application, protocol)"
                + " ORDER BY (tenant, organisation, timestamp, zone, application, protocol,"
                + " samplingInterval)"
                + " PARTITION BY toYYYYMM(timestamp)").get();
        admin.execute("CREATE MATERIALIZED VIEW " + view + " TO " + target + " AS SELECT"
                + " f.tenant AS tenant, f.organisation AS organisation,"
                + " toStartOfMinute(f.timestamp) AS timestamp, f.zone AS zone,"
                + " ifNull(f.application, '') AS application, f.protocol AS protocol,"
                + " f.samplingInterval AS samplingInterval,"
                + " sum(f.bytes) AS bytes, sum(f.packets) AS packets, count() AS flowCount,"
                + " sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn,"
                + " sumIf(f.bytes, f.direction = 'EGRESS') AS bytesOut,"
                + " sumIf(f.packets, f.direction = 'INGRESS') AS packetsIn,"
                + " sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut"
                + " FROM " + FlowsSchema.qualifiedFlows(DATABASE) + " AS f"
                + " GROUP BY tenant, organisation, timestamp, zone, application, protocol,"
                + " samplingInterval").get();
    }
}
