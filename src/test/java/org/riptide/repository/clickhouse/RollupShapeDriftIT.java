/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.google.common.base.Throwables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.provisioning.ProvisioningDdl;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rollup shape drift against a real server, through a real scoped writer (#470).
 *
 * <p><b>Why the writer and not {@code default}.</b> The collector connects as the provisioned
 * writer, and that role sees a filtered {@code system.tables}: ClickHouse hides objects it holds no
 * grant on rather than refusing the query, so an ungranted materialized view reads as zero rows —
 * the same thing an absent view reads as. Running this through an all-privileges user would make
 * every assertion here pass while telling us nothing about the path operators run. That blindness
 * is exactly what hid the grant problem from this change's first draft.</p>
 *
 * <p><b>The #587 probe.</b> The tests from {@link #anAbsentViewAnswersItsRecordedCode()} down ask
 * this same server the question #587 rests on: does a trivial query against a materialized view
 * answer a different code when the view is absent than when it exists and the user holds no grant
 * on it? {@code RollupShapeCheck} states so in a comment; nothing had ever measured it. They share
 * this container because it is already the right one, and use their own database and user so
 * nothing here touches the writer fixture above. See {@link #PROBE_USER} for why the probe is
 * modelled on a provisioned writer and not on {@code writer}.</p>
 */
@Testcontainers
public class RollupShapeDriftIT {

    private static final String DATABASE = "drift";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static String endpoint;
    private static Client admin;

    // ---- #587 probe fixture -------------------------------------------------------------------

    private static final String PROBE_DATABASE = "grants_probe";

    /** The rollup target the probe user may write, exactly as {@code flow_writer} holds it. */
    private static final String PROBE_TARGET = PROBE_DATABASE + ".target";

    /** A materialized view that exists, and which {@link #PROBE_USER} holds no grant on. */
    private static final String UNGRANTED_VIEW = PROBE_DATABASE + ".present_mv";

    /** A materialized view of the same shape, in the same database, that was never created. */
    private static final String ABSENT_VIEW = PROBE_DATABASE + ".absent_mv";

    /** A materialized view in a database that was never created: a dropped or never-onboarded tenant. */
    private static final String VIEW_IN_ABSENT_DATABASE = PROBE_DATABASE + "_missing.absent_mv";

    /**
     * A user modelled on a provisioned writer, because "ungranted" has degrees and the degree is
     * load-bearing. {@link #theWriterCanSeeTheViewButNotReadThroughIt()} exercises <em>holds
     * {@code SHOW TABLES}, lacks {@code SELECT}</em>, and pins the server's message rather than its
     * code. The case #587 actually faces is <em>no grant on the view at all</em>: that is what makes
     * {@code system.tables} return zero rows for it ({@code ClickhouseRepository.readRollupSelects}),
     * which is the blindness #587 exists to fix. So this user holds {@code INSERT} on the rollup
     * target and nothing whatsoever on the view, and {@link #provisionProbe()} asserts that rather
     * than assuming it.
     */
    private static final String PROBE_USER = "probe_writer";

    /**
     * Every grant the probe user holds, derived from the constants above so a rename cannot fail the
     * grant assertion for the wrong reason. Listed so the "no grant on the view" case cannot quietly
     * become some other case: a set that has emptied out says the {@code system.grants} read is
     * broken rather than that the user is unprivileged, and a set that has grown could carry a grant
     * on the view, which would silently degrade this probe into the "can see but cannot read"
     * degree above. No grant on {@code system.tables}: a writer reads the access-filtered catalog
     * without one, as {@link #theWriterCanSeeTheViewButNotReadThroughIt()} shows.
     */
    private static final Set<String> PROBE_USER_ACCESS = Set.of("INSERT ON " + PROBE_TARGET);

    /**
     * What the server answers for a view that does not exist in a database that does:
     * {@code UNKNOWN_TABLE}. The client library names this code; the server pinned in
     * {@code .github/e2e-images/clickhouse.Dockerfile} is asked whether it still answers it.
     */
    private static final int ABSENT_VIEW_CODE = ServerException.ErrorCodes.TABLE_NOT_FOUND.getCode();

    /**
     * What the server answers for a view whose database does not exist: {@code UNKNOWN_DATABASE}.
     * The third outcome, which a branch on {@link #ABSENT_VIEW_CODE} alone would fall through.
     */
    private static final int ABSENT_DATABASE_CODE = ServerException.ErrorCodes.DATABASE_NOT_FOUND.getCode();

    /**
     * What the server answers for a view that exists and which the connecting user holds no grant
     * on: {@code ACCESS_DENIED}. A literal because client-v2 0.10.0 has no enum member for it.
     */
    private static final int UNGRANTED_VIEW_CODE = 497;

    private static Client probe;

    /** The server that answered, read from it rather than from the image tag. */
    private static String serverVersion;

    private static Answer absent;
    private static Answer absentDatabase;
    private static Answer ungranted;

    @BeforeAll
    static void provision() throws Exception {
        endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        admin = new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername("default")
                .setPassword("")
                .setDefaultDatabase("default")
                .build();

        // Manage mode as the admin creates the schema; then the shared roles and a scoped writer,
        // exactly as `riptide onboard` would.
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config("default", "", true), RESOLVERS)
                .start();
        for (final String ddl : ProvisioningDdl.ensureShared(DATABASE, 1L)) {
            admin.execute(ddl).get();
        }
        admin.execute("CREATE USER IF NOT EXISTS writer IDENTIFIED WITH plaintext_password BY 'pw'").get();
        admin.execute("GRANT flow_writer TO writer").get();

        provisionProbe();
    }

    /**
     * The #587 fixture: a target, one view on it, a user holding only {@code INSERT} on the target.
     * Everything "absent" and "ungranted" mean is asserted at the end, not assumed, because the
     * distinguishability verdict is the headline a reader takes from the failsafe output and a
     * fixture that had drifted would make it accuse ClickHouse of collapsing two states the fixture
     * never set up. The three refusals are read once here: nothing mutates this fixture, so asking
     * again per test would only add round trips.
     */
    private static void provisionProbe() throws Exception {
        serverVersion = admin.queryAll("SELECT version() AS v").getFirst().getString("v");

        admin.execute("CREATE DATABASE IF NOT EXISTS " + PROBE_DATABASE).get();
        admin.execute("CREATE TABLE " + PROBE_DATABASE + ".source (t DateTime, bytes UInt64)"
                + " ENGINE = MergeTree ORDER BY t").get();
        admin.execute("CREATE TABLE " + PROBE_TARGET + " (t DateTime, bytes UInt64)"
                + " ENGINE = SummingMergeTree ORDER BY t").get();
        // TO <target>, the shape every riptide rollup view has: the view is a name with no data of
        // its own, which is precisely why an ungranted one is invisible rather than empty.
        admin.execute("CREATE MATERIALIZED VIEW " + UNGRANTED_VIEW + " TO " + PROBE_TARGET
                + " AS SELECT t, sum(bytes) AS bytes FROM " + PROBE_DATABASE + ".source GROUP BY t").get();

        admin.execute("CREATE USER " + PROBE_USER + " IDENTIFIED WITH no_password").get();
        admin.execute("GRANT INSERT ON " + PROBE_TARGET + " TO " + PROBE_USER).get();

        assertThat(materializedViewsNamed(ABSENT_VIEW))
                .as("%s must not exist, or the absent half asks the same question as the other one",
                        ABSENT_VIEW)
                .isZero();
        assertThat(databasesNamed(PROBE_DATABASE + "_missing"))
                .as("the database of %s must not exist, or that half asks the same question as %s",
                        VIEW_IN_ABSENT_DATABASE, ABSENT_VIEW)
                .isZero();
        assertThat(materializedViewsNamed(UNGRANTED_VIEW))
                .as("%s must exist, or the ungranted half silently becomes the absent case",
                        UNGRANTED_VIEW)
                .isEqualTo(1);
        assertThat(accessHeldBy(PROBE_USER))
                .as("the probe holds no grant on %s: not SELECT, not SHOW TABLES, nothing",
                        UNGRANTED_VIEW)
                .containsExactlyInAnyOrderElementsOf(PROBE_USER_ACCESS);

        probe = new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername(PROBE_USER)
                .setPassword("")
                .setDefaultDatabase("default")
                .build();
        // default and the probe both authenticate with an empty password, so a builder slip that
        // connected as the admin would leave every refusal below describing the wrong user.
        assertThat(probe.queryAll("SELECT currentUser() AS u").getFirst().getString("u"))
                .as("the probe must connect as %s", PROBE_USER)
                .isEqualTo(PROBE_USER);
        assertThat(probe.queryAll("SELECT count() AS c FROM system.tables WHERE database = '"
                        + PROBE_DATABASE + "' AND engine = 'MaterializedView'").getFirst().getLong("c"))
                .as("an ungranted view reads as zero rows in the access-filtered catalog, which is"
                        + " what ClickhouseRepository.readRollupSelects sees and cannot interpret")
                .isZero();

        absent = refusalOf(ABSENT_VIEW);
        absentDatabase = refusalOf(VIEW_IN_ABSENT_DATABASE);
        ungranted = refusalOf(UNGRANTED_VIEW);
    }

    private static ClickhouseConfig config(final String user, final String password, final boolean manage) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint);
        config.setDatabase(DATABASE);
        config.setUsername(SecretRef.of(user));
        // An unset ref binds the empty password, which is what the container's `default` user has;
        // SecretRef.of("") is rejected outright.
        if (!password.isEmpty()) {
            config.setPassword(SecretRef.of(password));
        }
        config.setManageSchema(manage);
        config.setAsyncInserts(false);
        return config;
    }

    /** Start as the scoped writer in provisioned mode, capturing what the repository logs. */
    private static List<ILoggingEvent> startWriterAndCapture() {
        final Logger logger = (Logger) LoggerFactory.getLogger(ClickhouseRepository.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(),
                    config("writer", "pw", false), RESOLVERS).start();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static List<String> messages(final List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * A deployment provisioned by this version is silent. Guards the normalisation: if the emitted
     * SELECT did not compare equal to what the server stores, this would warn about all four
     * rollups on every start — and an operator who learns to ignore the warning is worse off than
     * one who never had it.
     */
    @Test
    void aCurrentDeploymentReportsNothingAndKeepsItsRollups() {
        RollupAvailability.recordDrifted(List.of());

        final List<String> logged = messages(startWriterAndCapture());

        assertThat(logged).noneMatch(m -> m.contains("does not match this version's schema"));
        assertThat(logged).noneMatch(m -> m.contains("could not be verified"));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup))).isTrue();
        }
    }

    /**
     * The case a column comparison cannot see: the aggregate changed, every column name did not.
     * Also the routing consequence — detection that left the rollup reachable would only write the
     * wrong answer into a log while continuing to serve it.
     */
    @Test
    void aChangedViewExpressionIsReportedAndTheRollupIsDeclined() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("DROP VIEW IF EXISTS " + mv).get();
        admin.execute("CREATE MATERIALIZED VIEW " + mv + " TO " + FlowsSchema.qualifiedRollup(DATABASE, rollup)
                + " AS " + FlowsSchema.rollupSelects(DATABASE).get(rollup)
                        .replace("sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut",
                                "sum(f.packets) AS packetsOut")).get();
        admin.execute("GRANT SHOW TABLES ON " + mv + " TO flow_writer").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged).anyMatch(m -> m.contains(rollup) && m.contains("does not match"));
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup))).isFalse();
            assertThat(QueryRouter.resolveTopTalkersTable(DATABASE, 120, "application"))
                    .isEqualTo(FlowsSchema.qualifiedFlows(DATABASE));
            // one stale rollup must not cost the other three
            assertThat(QueryRouter.resolveInterfaceTable(DATABASE, 120))
                    .isEqualTo(FlowsSchema.qualifiedRollup(DATABASE, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE));
        } finally {
            admin.execute("DROP VIEW IF EXISTS " + mv).get();
            admin.execute(FlowsSchema.createRollupViews(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + "_mv")).findFirst().orElseThrow()).get();
            admin.execute("GRANT SHOW TABLES ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    /**
     * The grant problem, and the reason it needs its own outcome. Without the grant the view is
     * zero rows — indistinguishable from absent — so calling it stale would condemn every
     * deployment provisioned before that grant existed, and calling it fine would be silent on real
     * drift.
     */
    @Test
    void aViewTheWriterCannotSeeIsReportedAsUnverifiableAndStillUsed() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_CONVERSATION;
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("REVOKE SHOW TABLES ON " + mv + " FROM flow_writer").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged)
                    .as("an unreadable view must name the grant, not accuse the rollup")
                    .anyMatch(m -> m.contains(rollup) && m.contains("could not be verified")
                            && m.contains("GRANT SHOW TABLES"));
            assertThat(logged).noneMatch(m -> m.contains(rollup) && m.contains("does not match"));
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup)))
                    .as("a rollup that could not be checked is not thereby known to be wrong")
                    .isTrue();
        } finally {
            admin.execute("GRANT SHOW TABLES ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    /**
     * A rollup target that does not exist is declined, and startup still succeeds.
     *
     * <p>Two things at once. The verdict must be UNREACHABLE rather than UNVERIFIABLE, because a
     * query routed at a missing table fails with {@code UNKNOWN_TABLE} — the one case where the
     * check can turn a hard query error into a graceful raw-flows fallback. And the read itself
     * must not take the collector down: this is the shape of the failure that
     * {@code CompletableFuture.get()}'s checked exceptions would cause if the catch were narrowed
     * back to {@code RuntimeException}.
     */
    @Test
    void anAbsentTargetTableIsDeclinedAndStartupStillSucceeds() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_EXPORTER_IFACE;
        final String target = FlowsSchema.qualifiedRollup(DATABASE, rollup);
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("DROP VIEW IF EXISTS " + mv).get();
        admin.execute("DROP TABLE IF EXISTS " + target).get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged).anyMatch(m -> m.contains(rollup) && m.contains("cannot be reached"));
            assertThat(RollupAvailability.usable(target))
                    .as("routing at a missing table would fail the query outright")
                    .isFalse();
            assertThat(QueryRouter.resolveInterfaceTable(DATABASE, 120))
                    .isEqualTo(FlowsSchema.qualifiedFlows(DATABASE));
        } finally {
            admin.execute(FlowsSchema.createRollupTables(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + " ")).findFirst().orElseThrow()).get();
            admin.execute(FlowsSchema.createRollupViews(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + "_mv")).findFirst().orElseThrow()).get();
            admin.execute("GRANT INSERT ON " + target + " TO flow_writer").get();
            admin.execute("GRANT SHOW TABLES ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    /**
     * The writer must not be able to read rollup data through the view's name.
     *
     * <p>A row policy on a rollup target does not apply through its materialized view, and
     * {@code flow_writer} is shared by every per-tenant writer — so a {@code SELECT} grant on the
     * {@code _mv} would be a cross-tenant read path around the policy. This is what forced the
     * grant to {@code SHOW TABLES}.</p>
     */
    @Test
    void theWriterCanSeeTheViewButNotReadThroughIt() throws Exception {
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, FlowsSchema.ROLLUP_BY_GEO_ASN);
        final Client writer = new Client.Builder()
                .addEndpoint(endpoint).setUsername("writer").setPassword("pw")
                .setDefaultDatabase(DATABASE).build();
        try (writer) {
            // Counted server-side rather than by walking the rows: the walk needed a loop variable
            // it never read, which is a CodeQL finding and a worse way to ask the question anyway.
            final long visible;
            try (var records = writer.queryRecords("SELECT count() AS visible FROM system.tables"
                    + " WHERE database = '" + DATABASE + "' AND engine = 'MaterializedView'").get()) {
                visible = records.iterator().next().getLong("visible");
            }
            assertThat(visible).as("the check needs the views visible").isPositive();

            assertThatThrownBy(() -> writer.queryRecords("SELECT count() FROM " + mv).get())
                    .as("SELECT through the view would bypass the target's row policy")
                    .hasMessageContaining("Not enough privileges");
        }
    }

    /** A column the running version expects and the table never got. */
    @Test
    void aMissingTargetColumnIsReported() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_GEO_ASN;
        final String target = FlowsSchema.qualifiedRollup(DATABASE, rollup);
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("DROP VIEW IF EXISTS " + mv).get();
        admin.execute("ALTER TABLE " + target + " DROP COLUMN packetsOut").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged).anyMatch(m -> m.contains(rollup) && m.contains("missing")
                    && m.contains("packetsOut"));
            assertThat(RollupAvailability.usable(target)).isFalse();
        } finally {
            admin.execute("ALTER TABLE " + target + " ADD COLUMN IF NOT EXISTS packetsOut UInt64").get();
            admin.execute(FlowsSchema.createRollupViews(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + "_mv")).findFirst().orElseThrow()).get();
            admin.execute("GRANT SHOW TABLES ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    // ---- #587 probe: does the server tell an absent view from an ungranted one? ----------------

    /**
     * The absent half: a view that was never created, in a database that exists.
     *
     * <p>Asked in the same database, by the same user, with the same statement as the ungranted
     * half, so the only thing that differs between the two is whether the object exists. If this
     * fails the server changed its answer; the failure carries the new code and the server's own
     * message, so read those rather than assuming the test is wrong.</p>
     */
    @Test
    void anAbsentViewAnswersItsRecordedCode() {
        assertThat(absent.code())
                .as("#587: querying the absent view %s was recorded as answering error code %d;"
                        + " ClickHouse %s answers %s", ABSENT_VIEW, ABSENT_VIEW_CODE, serverVersion,
                        absent)
                .isEqualTo(ABSENT_VIEW_CODE);
    }

    /**
     * The other absent half: a view whose database was never created. A tenant that was dropped or
     * never onboarded looks like this, and it answers a third code, so a branch written for
     * {@link #ABSENT_VIEW_CODE} alone would fall through it.
     */
    @Test
    void aViewInAnAbsentDatabaseAnswersItsRecordedCode() {
        assertThat(absentDatabase.code())
                .as("#587: querying %s, whose database does not exist, was recorded as answering"
                        + " error code %d; ClickHouse %s answers %s", VIEW_IN_ABSENT_DATABASE,
                        ABSENT_DATABASE_CODE, serverVersion, absentDatabase)
                .isEqualTo(ABSENT_DATABASE_CODE);
    }

    /**
     * The ungranted half: a view that exists, held by a user with no grant on it at all. What makes
     * it the ungranted case rather than some other one is asserted by {@link #provisionProbe()}.
     */
    @Test
    void aViewTheUserHoldsNoGrantOnAnswersItsRecordedCode() {
        assertThat(ungranted.code())
                .as("#587: querying the ungranted view %s was recorded as answering error code %d;"
                        + " ClickHouse %s answers %s", UNGRANTED_VIEW, UNGRANTED_VIEW_CODE,
                        serverVersion, ungranted)
                .isEqualTo(UNGRANTED_VIEW_CODE);
    }

    /**
     * The question #587 actually rests on: is the ungranted answer different from both absent ones?
     *
     * <p>Not "is the code 497". {@code RollupShapeCheck} needs to tell absent from ungranted; if
     * they shared a code no branch could be written, and #587 would be unimplementable as designed
     * however the codes were spelled. The lines it prints are the go/no-go a reader can take from
     * the failsafe output without opening this file.</p>
     *
     * <p>This test deliberately does not re-check the recorded constants: the three tests above pin
     * them, and re-asserting them here would run first and make this assertion, the one whose
     * message a reader is told to believe, unreachable. A drift in any constant fails its own test;
     * a collapse of two states fails this one.</p>
     */
    @Test
    void theUngrantedCaseIsDistinguishableFromBothAbsentOnes() {
        final boolean collapsed = ungranted.code() == absent.code()
                || ungranted.code() == absentDatabase.code();
        System.out.println("#587 probe on ClickHouse " + serverVersion + ": an absent view answers"
                + " error code " + absent.code() + ", a view in an absent database answers error code "
                + absentDatabase.code() + ", a view the user holds no grant on answers error code "
                + ungranted.code() + ": " + (collapsed
                        ? "A SHARED CODE, so the states are indistinguishable and #587 is"
                                + " UNIMPLEMENTABLE as designed"
                        : "distinct codes, so RollupShapeCheck can branch on them and #587 is"
                                + " implementable"));
        System.out.println("  absent          " + ABSENT_VIEW + " -> " + absent.message());
        System.out.println("  absent database " + VIEW_IN_ABSENT_DATABASE + " -> " + absentDatabase.message());
        System.out.println("  ungranted       " + UNGRANTED_VIEW + " -> " + ungranted.message());

        assertThat(ungranted.code())
                .as("on ClickHouse %s an absent view answers %s, a view in an absent database answers"
                        + " %s, and an ungranted one answers %s. #587 is implementable only while the"
                        + " ungranted code differs from both absent ones: a shared code means the"
                        + " states cannot be told apart and the issue is unimplementable as designed,"
                        + " needing reframing or closing rather than a fix here",
                        serverVersion, absent, absentDatabase, ungranted)
                .isNotIn(absent.code(), absentDatabase.code());
    }

    /** One server refusal: the code #587 would branch on, and the message that gives it meaning. */
    private record Answer(int code, String message) {

        @Override
        public String toString() {
            return "error code " + this.code + " (" + this.message + ")";
        }
    }

    /**
     * The server's refusal for a trivial query against {@code view}, run as the probe user through
     * {@code queryRecords(...).get()}, the path {@code ClickhouseRepository.readRollupSelects} reads
     * on. That path wraps the {@link ServerException} in an {@code ExecutionException}, hence the
     * walk down the cause chain.
     */
    private static Answer refusalOf(final String view) {
        final Throwable thrown = catchThrowable(() -> {
            try (var records = probe.queryRecords("SELECT count() FROM " + view).get()) {
                // The refusal this probe reads is thrown by get(); on the path where it is not,
                // the assertion below is what fails, and the resource still closes.
                assertThat(records).isNotNull();
            }
        });
        assertThat(thrown)
                .as("querying %s as %s must be refused; a query that succeeded recorded no error"
                        + " code and settles nothing", view, PROBE_USER)
                .isNotNull();
        final ServerException server = Throwables.getCausalChain(thrown).stream()
                .filter(ServerException.class::isInstance)
                .map(ServerException.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("querying " + view + " failed without a"
                        + " ServerException anywhere in its cause chain, so the server's error code,"
                        + " the only thing this probe reads, was never available: " + thrown, thrown));
        return new Answer(server.getCode(), server.getMessage());
    }

    /**
     * Every grant the user holds, rendered as {@code <ACCESS_TYPE> ON <database>.<table>[.<column>]}.
     *
     * <p>Partial revokes are excluded: {@code system.grants} records one as an ordinary row with
     * {@code is_partial_revoke} set, so including them would render a revoked privilege as a held
     * one.</p>
     *
     * <p>The second query does <em>not</em> expand a role's privileges. It records only that a role
     * is granted at all, as {@code ROLE <name>}. That is enough for the caller, whose exact-match
     * assertion fails on any role appearing: the probe is meant to hold none, so a privilege
     * arriving through one is caught by the role's presence without the set having to describe it.
     * A test that expected a role would need the expansion this does not do.</p>
     */
    private static Set<String> accessHeldBy(final String user) throws Exception {
        final Set<String> held = new TreeSet<>();
        try (var records = admin.queryRecords("SELECT concat(toString(access_type), ' ON ',"
                + " ifNull(database, '*'), '.', ifNull(table, '*'),"
                + " if(column IS NULL, '', concat('.', column))) AS held FROM system.grants"
                + " WHERE user_name = '" + user + "' AND is_partial_revoke = 0").get()) {
            records.forEach(record -> held.add(record.getString("held")));
        }
        try (var records = admin.queryRecords("SELECT concat('ROLE ', granted_role_name) AS held"
                + " FROM system.role_grants WHERE user_name = '" + user + "'").get()) {
            records.forEach(record -> held.add(record.getString("held")));
        }
        return held;
    }

    /** How many materialized views the admin can see under that exact name. */
    private static long materializedViewsNamed(final String qualified) throws Exception {
        final int dot = qualified.indexOf('.');
        return admin.queryAll("SELECT count() AS c FROM system.tables WHERE database = '"
                + qualified.substring(0, dot) + "' AND name = '" + qualified.substring(dot + 1)
                + "' AND engine = 'MaterializedView'").getFirst().getLong("c");
    }

    /** How many databases the admin can see under that exact name. */
    private static long databasesNamed(final String database) throws Exception {
        return admin.queryAll("SELECT count() AS c FROM system.databases WHERE name = '"
                + database + "'").getFirst().getLong("c");
    }
}
