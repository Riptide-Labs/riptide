/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.e2e.ContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Whether a real server tells an absent materialized view from an ungranted one (#587).
 *
 * <p>{@code RollupShapeCheck} states, in a production comment it has never acted on, that "a trivial
 * query against the view answers UNKNOWN_TABLE when it is absent and ACCESS_DENIED when it is merely
 * ungranted". #587 is the issue that would act on it. Nothing had ever asked a server, so the design
 * of an M-sized issue rested on a belief about ClickHouse — and the sharp question is not which code
 * appears but whether the two <em>differ</em>: if both cases answer the same code, no branch can be
 * written and #587 is unimplementable as designed whatever that code is.</p>
 *
 * <p>The probe user is modelled on a provisioned writer, because "ungranted" has degrees and the
 * degree is load-bearing. {@code RollupShapeDriftIT.theWriterCanSeeTheViewButNotReadThroughIt}
 * exercises <em>holds {@code SHOW TABLES}, lacks {@code SELECT}</em> — but it pins the server's
 * <em>message</em> ("Not enough privileges") and not its code, so no code is recorded for that
 * degree anywhere. The untested case, and the one #587 actually faces, is <em>no grant on the view
 * at all</em>: that is what makes {@code system.tables} return zero rows for it
 * ({@code ClickhouseRepository.readRollupSelects}), which is the blindness #587 exists to fix. So
 * the user here holds {@code INSERT} on the rollup target and nothing whatsoever on the view, and
 * the test asserts that rather than assuming it.</p>
 *
 * <p><b>What this is not.</b> It is a regression test against a pinned server version, not a proof.
 * It records what the image in {@code .github/e2e-images/clickhouse.Dockerfile} answers and pins it,
 * so a version bump that changes either code turns the e2e job red — and only the e2e job: {@code
 * *IT} classes run under the {@code e2e} Maven profile alone, so {@code make jar} and a plain
 * {@code mvn verify} execute none of this. It does not prove #587 correct, implement any part of it,
 * or record a code for the partially-granted degree above. It lives beside the tenancy ITs because
 * it is a grants question — the same neighbourhood as #649's provisioning work — and it settles
 * nothing for #649.</p>
 */
@Testcontainers
public class AbsentVersusUngrantedViewIT {

    private static final String DATABASE = "grants_probe";

    /** The rollup target the probe user may write, exactly as {@code flow_writer} holds it. */
    private static final String TARGET = DATABASE + ".target";

    /** A materialized view that exists, and which {@link #PROBE_USER} holds no grant on. */
    private static final String UNGRANTED_VIEW = DATABASE + ".present_mv";

    /** A materialized view of the same shape, in the same database, that was never created. */
    private static final String ABSENT_VIEW = DATABASE + ".absent_mv";

    private static final String PROBE_USER = "probe_writer";

    /**
     * Every grant the probe user holds, derived from the constants above so a rename cannot fail the
     * grant assertion for the wrong reason. Listed so the "no grant on the view" case cannot quietly
     * become some other case: a set that has emptied out says the {@code system.grants} read is
     * broken rather than that the user is unprivileged, and a set that has grown could carry a grant
     * on the view — which would silently degrade this probe into the "can see but cannot read"
     * degree {@code RollupShapeDriftIT} exercises.
     */
    private static final Set<String> PROBE_USER_ACCESS =
            Set.of("INSERT ON " + TARGET, "SELECT ON system.tables");

    /**
     * What the server answers for a view that does not exist: {@code UNKNOWN_TABLE}.
     *
     * <p>Read off a server, not predicted — measured against the image pinned in
     * {@code .github/e2e-images/clickhouse.Dockerfile}, whose running version every failure message
     * here names. If this fails the server changed its answer; the failure carries the new code and
     * the server's own message, so read those rather than assuming the test is wrong.</p>
     */
    private static final int ABSENT_VIEW_CODE = 60;

    /**
     * What the server answers for a view that exists and which the connecting user holds no grant
     * on: {@code ACCESS_DENIED}.
     *
     * <p>Read off a server, not predicted — measured against the image pinned in
     * {@code .github/e2e-images/clickhouse.Dockerfile}. If this fails the server changed its answer,
     * and {@link #theTwoCasesAreDistinguishable()} is the assertion that says what the new answer
     * costs #587.</p>
     */
    private static final int UNGRANTED_VIEW_CODE = 497;

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            // access management lets the default (admin) user CREATE USER and GRANT.
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static Client admin;

    /** The server that answered, read from it rather than from the image tag. */
    private static String serverVersion;

    @BeforeAll
    static void provision() throws Exception {
        admin = new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername("default")
                .setPassword("")
                .setDefaultDatabase("default")
                .build();
        serverVersion = admin.queryAll("SELECT version() AS v").getFirst().getString("v");

        admin.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE).get();
        admin.execute("CREATE TABLE " + DATABASE + ".source (t DateTime, bytes UInt64)"
                + " ENGINE = MergeTree ORDER BY t").get();
        admin.execute("CREATE TABLE " + TARGET + " (t DateTime, bytes UInt64)"
                + " ENGINE = SummingMergeTree ORDER BY t").get();
        // TO <target>, the shape every riptide rollup view has: the view is a name with no data of
        // its own, which is precisely why an ungranted one is invisible rather than empty.
        admin.execute("CREATE MATERIALIZED VIEW " + UNGRANTED_VIEW + " TO " + TARGET
                + " AS SELECT t, sum(bytes) AS bytes FROM " + DATABASE + ".source GROUP BY t").get();

        admin.execute("CREATE USER " + PROBE_USER + " IDENTIFIED WITH no_password").get();
        admin.execute("GRANT INSERT ON " + TARGET + " TO " + PROBE_USER).get();
        // So the probe can look itself up in the access-filtered catalog the way the repository
        // does. Deliberately not a grant on either view.
        admin.execute("GRANT SELECT ON system.tables TO " + PROBE_USER).get();
    }

    /**
     * The absent half: a view that was never created.
     *
     * <p>Asked in the same database, by the same user, with the same statement as the ungranted
     * half, so the only thing that differs between the two is whether the object exists.</p>
     */
    @Test
    void anAbsentViewAnswersItsRecordedCode() throws Exception {
        assertTheFixtureIsWhatItClaims();

        final Answer answer = errorAnswerFor(ABSENT_VIEW);
        assertThat(answer.code())
                .as("#587: querying the absent view %s was recorded as answering error code %d;"
                        + " ClickHouse %s answers %s", ABSENT_VIEW, ABSENT_VIEW_CODE, serverVersion,
                        answer)
                .isEqualTo(ABSENT_VIEW_CODE);
    }

    /**
     * The ungranted half: a view that exists, held by a user with no grant on it at all.
     *
     * <p>What makes it the ungranted case rather than some other one is asserted by
     * {@link #assertTheFixtureIsWhatItClaims()}, not assumed here.</p>
     */
    @Test
    void aViewTheUserHoldsNoGrantOnAnswersItsRecordedCode() throws Exception {
        assertTheFixtureIsWhatItClaims();

        final Answer answer = errorAnswerFor(UNGRANTED_VIEW);
        assertThat(answer.code())
                .as("#587: querying the ungranted view %s was recorded as answering error code %d;"
                        + " ClickHouse %s answers %s", UNGRANTED_VIEW, UNGRANTED_VIEW_CODE,
                        serverVersion, answer)
                .isEqualTo(UNGRANTED_VIEW_CODE);
    }

    /**
     * The question #587 actually rests on: are the two answers different at all?
     *
     * <p>Not "is the code 497". {@code RollupShapeCheck} needs to tell absent from ungranted; if both
     * answered the same code no branch could be written, and #587 would be unimplementable as
     * designed however the codes were spelled. The lines it prints are the go/no-go a reader can
     * take from the failsafe output without opening this file.</p>
     *
     * <p>Both codes are checked against their recorded constants before the pair is compared, and
     * the fixture is asserted before either is read. Otherwise this test could print a go for two
     * codes that had both drifted to new values, or blame the server for a broken fixture — and this
     * is the one test whose failure message a reader is told to believe.</p>
     */
    @Test
    void theTwoCasesAreDistinguishable() throws Exception {
        assertTheFixtureIsWhatItClaims();

        final Answer absent = errorAnswerFor(ABSENT_VIEW);
        final Answer ungranted = errorAnswerFor(UNGRANTED_VIEW);
        System.out.println("#587 probe on ClickHouse " + serverVersion + ": an absent view answers"
                + " error code " + absent.code() + ", a view the user holds no grant on answers"
                + " error code " + ungranted.code() + " — " + (absent.code() == ungranted.code()
                        ? "THE SAME CODE, so the two are indistinguishable and #587 is"
                                + " UNIMPLEMENTABLE as designed"
                        : "different codes, so RollupShapeCheck can branch on them and #587 is"
                                + " implementable"));
        System.out.println("  absent    " + ABSENT_VIEW + " -> " + absent.message());
        System.out.println("  ungranted " + UNGRANTED_VIEW + " -> " + ungranted.message());

        assertThat(absent.code())
                .as("the absent view's recorded code, checked here too so a pair that drifted to two"
                        + " new-but-unequal codes cannot print a go: ClickHouse %s answers %s",
                        serverVersion, absent)
                .isEqualTo(ABSENT_VIEW_CODE);
        assertThat(ungranted.code())
                .as("the ungranted view's recorded code, checked here too for the same reason:"
                        + " ClickHouse %s answers %s", serverVersion, ungranted)
                .isEqualTo(UNGRANTED_VIEW_CODE);
        assertThat(ungranted.code())
                .as("on ClickHouse %s an absent view answers %s and an ungranted one answers %s."
                        + " #587 is implementable only while those differ: equal codes mean the two"
                        + " states cannot be told apart and the issue is unimplementable as"
                        + " designed, needing reframing or closing rather than a fix here",
                        serverVersion, absent, ungranted)
                .isNotEqualTo(absent.code());
    }

    /**
     * Everything the two halves mean by "absent" and "ungranted", asserted rather than assumed.
     *
     * <p>Shared by all three tests because the pair test's failure message is the headline a reader
     * is told to take from the failsafe output: a fixture that had drifted would otherwise make it
     * accuse ClickHouse of collapsing two states the fixture never set up.</p>
     *
     * <p>The identity check is not ceremony. {@code default} and the probe both authenticate with an
     * empty password, so a builder slip that connected as the admin would leave every other
     * assertion here describing a user this test is not asking about — and the probe's identity is
     * the entire premise.</p>
     */
    private static void assertTheFixtureIsWhatItClaims() throws Exception {
        assertThat(materializedViewsNamed(ABSENT_VIEW))
                .as("%s must not exist, or the absent half asks the same question as the other one",
                        ABSENT_VIEW)
                .isZero();
        assertThat(materializedViewsNamed(UNGRANTED_VIEW))
                .as("%s must exist, or the ungranted half silently becomes the absent case",
                        UNGRANTED_VIEW)
                .isEqualTo(1);
        assertThat(accessHeldBy(PROBE_USER))
                .as("the probe holds no grant on %s — not SELECT, not SHOW TABLES, nothing",
                        UNGRANTED_VIEW)
                .containsExactlyInAnyOrderElementsOf(PROBE_USER_ACCESS);

        try (Client probe = probeClient()) {
            assertThat(probe.queryAll("SELECT currentUser() AS u").getFirst().getString("u"))
                    .as("the probe must connect as %s; %s and the admin share an empty password, so"
                            + " a client built wrong would ask this question as the wrong user",
                            PROBE_USER, PROBE_USER)
                    .isEqualTo(PROBE_USER);
            assertThat(probe.queryAll("SELECT count() AS c FROM system.tables WHERE database = '"
                            + DATABASE + "' AND engine = 'MaterializedView'").getFirst().getLong("c"))
                    .as("an ungranted view reads as zero rows in the access-filtered catalog, which"
                            + " is what ClickhouseRepository.readRollupSelects sees and cannot"
                            + " interpret")
                    .isZero();
        }
    }

    /** One server refusal: the code #587 would branch on, and the message that gives it meaning. */
    private record Answer(int code, String message) {

        @Override
        public String toString() {
            return "error code " + this.code + " (" + this.message + ")";
        }
    }

    /**
     * The server's refusal for a trivial query against {@code view}, run as the probe user.
     *
     * <p>Asked twice, because the code is only worth pinning on the path that will read it.
     * {@code ClickhouseRepository.readRollupSelects} uses {@code queryRecords(...).get()}, which
     * wraps a {@code ServerException} in an {@code ExecutionException}, while {@code queryAll}
     * throws it directly — different enough that pinning one says nothing about the other. The
     * production path's answer is the one returned; the direct path is asserted to agree, so the
     * day they diverge is a failure rather than a silently wrong recording.</p>
     */
    private static Answer errorAnswerFor(final String view) throws Exception {
        final Answer production = refusalOf(view, "queryRecords(...).get()",
                probe -> {
                    try (var records = probe.queryRecords("SELECT count() FROM " + view).get()) {
                        // The refusal this probe reads is thrown by get(); on the path where it is
                        // not, the assertion below is what fails, and the resource still closes.
                        assertThat(records).isNotNull();
                    }
                });
        final Answer direct = refusalOf(view, "queryAll(...)",
                probe -> probe.queryAll("SELECT count() FROM " + view));

        assertThat(direct.code())
                .as("the two client read paths must agree on %s, or the code recorded from the"
                        + " production path (%s) would not be the code #587 branches on when the"
                        + " check is reached some other way", view, production)
                .isEqualTo(production.code());
        return production;
    }

    /** A query issued as the probe user, expected to be refused. */
    @FunctionalInterface
    private interface ProbeQuery {
        void run(Client probe) throws Exception;
    }

    /** Runs {@code query} as the probe user and reads the server's refusal out of what it threw. */
    private static Answer refusalOf(final String view, final String path, final ProbeQuery query) {
        try (Client probe = probeClient()) {
            final Throwable thrown = catchThrowable(() -> query.run(probe));
            assertThat(thrown)
                    .as("querying %s as %s via %s must be refused; a query that succeeded recorded"
                            + " no error code and settles nothing", view, PROBE_USER, path)
                    .isNotNull();
            final ServerException server = serverExceptionIn(thrown, view, path);
            return new Answer(server.getCode(), server.getMessage());
        }
    }

    /**
     * The {@link ServerException} in a throwable's cause chain, or an assertion failure naming why
     * not. The walk is depth-bounded because a self-referential cause chain is legal and would
     * otherwise hang the e2e job instead of failing it.
     */
    private static ServerException serverExceptionIn(final Throwable thrown, final String view,
            final String path) {
        Throwable cause = thrown;
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof ServerException server) {
                return server;
            }
            cause = cause.getCause();
        }
        throw new AssertionError("querying " + view + " via " + path + " failed without a"
                + " ServerException anywhere in its cause chain, so the server's error code — the"
                + " only thing this test reads — was never available: " + thrown, thrown);
    }

    /**
     * Every grant the user holds, rendered as {@code <ACCESS_TYPE> ON <database>.<table>[.<column>]}.
     *
     * <p>Partial revokes are excluded: {@code system.grants} records one as an ordinary row with
     * {@code is_partial_revoke} set, so including them would render a revoked privilege as a held
     * one.</p>
     *
     * <p>The second query does <em>not</em> expand a role's privileges — it records only that a role
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

    private static Client probeClient() {
        return new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername(PROBE_USER)
                .setPassword("")
                .setDefaultDatabase("default")
                .build();
    }

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
