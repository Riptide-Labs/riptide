/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.provisioning.ProvisioningCommand;
import org.riptide.provisioning.ProvisioningDdl;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.e2e.ContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * The {@code onboard}/{@code offboard} subcommands, proven end to end against a real ClickHouse.
 * Onboarding runs against a genuinely fresh server — nothing is pre-created, so
 * {@code onboard --create-schema} must bootstrap the database and {@code flows} table itself
 * (issues #246/#267; the collector only validates in {@code manage-schema=false} mode) before it
 * can grant and constrain them. Then the CLI is driven exactly
 * as an operator would: {@code onboard} a tenant, and the resulting scoped credentials must satisfy
 * the full isolation matrix — honest write persists, cross-tenant write is rejected (469,
 * {@code VIOLATED_CONSTRAINT}), the
 * reader sees only its tenant and cannot write/DDL — and {@code offboard} must revoke access.
 *
 * <p>Since #649 the isolation matrix also spans <em>databases</em>: the same tenant id onboarded
 * into two databases on one server must get two independent accounts, and neither may write into
 * the other's tables ({@link #aTenantOnboardedIntoTwoDatabasesGetsTwoIsolatedAccounts}). The upgrade
 * from the old unqualified naming is covered by
 * {@link #offboardRevokesAnInstanceProvisionedUnderTheOldNaming} and
 * {@link #onboardWarnsAboutALiveLegacyAccountWithoutDroppingIt}.
 *
 * <p>The server needs {@code custom_settings_prefixes: SQL_} for the CHECK barrier the onboarding
 * recipe installs; it is mounted from the classpath, as in {@link TenantWriteBarrierIT}.
 */
@Testcontainers
public class TenantOnboardingIT {

    private static final String DATABASE = "onb";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("clickhouse/custom-settings.xml"),
                    "/etc/clickhouse-server/config.d/custom-settings.xml")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    @Test
    void onboardedTenantWritesHonestlyAndIsIsolated() throws Exception {
        Assertions.assertThat(onboard("acme", "acme-eu", "wA", "rA")).isZero();
        Assertions.assertThat(onboard("other", "other-eu", "wO", "rO")).isZero();

        // Honest writes through the role-granted, CONST-pinned writers.
        writerRepository("acme", "wA").persist(List.of(flow("acme", "acme-eu", 31001)));
        writerRepository("other", "wO").persist(List.of(flow("other", "other-eu", 31002)));

        // Cross-tenant write (config lies about tenant) is rejected by the CHECK barrier.
        Assertions.assertThatThrownBy(() -> writerRepository("acme", "wA").persist(List.of(flow("evil", "acme-eu", 31003))))
                .hasStackTraceContaining(ClickhouseServerErrors.VIOLATED_CONSTRAINT_MESSAGE_PREFIX)
                .hasStackTraceContaining("VIOLATED_CONSTRAINT");

        // The reader sees only its own tenant (row policy) and cannot write or DDL (readonly role).
        try (var reader = rawClient(ProvisioningDdl.readerUser("acme", DATABASE), "rA")) {
            final var rows = reader.queryAll(
                    "SELECT tenant FROM " + DATABASE + ".flows WHERE srcPort IN (31001, 31002)");
            Assertions.assertThat(rows).hasSize(1);
            Assertions.assertThat(rows.getFirst().getString("tenant")).isEqualTo("acme");

            Assertions.assertThatThrownBy(
                            () -> reader.execute("INSERT INTO " + DATABASE + ".flows (tenant) VALUES ('acme')").get())
                    .hasStackTraceContaining("ACCESS_DENIED");
            Assertions.assertThatThrownBy(
                            () -> reader.execute("ALTER TABLE " + DATABASE + ".flows ADD COLUMN hacked String").get())
                    .hasStackTraceContaining("ACCESS_DENIED");
        }
    }

    @Test
    void onboardEmitsConfigStanzaAndIsIdempotent() {
        final var out = new ByteArrayOutputStream();
        final int code = ProvisioningCommand.run(
                onboardArgs("cfg", "cfg-eu", "wC", "rC"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                discard());
        Assertions.assertThat(code).isZero();
        Assertions.assertThat(out.toString(StandardCharsets.UTF_8))
                // The stanza is the operator's only copy of the username, and since #649 that name
                // carries the database — a stanza still naming `writer_cfg` would hand them a
                // credential that no longer exists.
                .contains("riptide.clickhouse.username=writer_cfg@" + DATABASE)
                .contains("riptide.clickhouse.password=wC")
                .contains("riptide.identity.tenant=cfg")
                .contains("riptide.identity.organisation=cfg-eu");

        // Re-running is a no-op that still succeeds (IF NOT EXISTS everywhere).
        Assertions.assertThat(onboard("cfg", "cfg-eu", "wC", "rC")).isZero();
    }

    @Test
    void reonboardRotatesTheWriterPassword() throws Exception {
        final String rotWriter = ProvisioningDdl.writerUser("rot", DATABASE);
        Assertions.assertThat(onboard("rot", "rot-eu", "pw1", "rr1")).isZero();
        try (var writer = rawClient(rotWriter, "pw1")) {
            Assertions.assertThat(writer.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // Re-onboard with a rotated writer secret: the new password must take effect...
        Assertions.assertThat(onboard("rot", "rot-eu", "pw2", "rr1")).isZero();
        try (var writer = rawClient(rotWriter, "pw2")) {
            Assertions.assertThat(writer.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }
        // ...and the old one must stop working.
        try (var stale = rawClient(rotWriter, "pw1")) {
            Assertions.assertThatThrownBy(() -> stale.queryAll("SELECT 1 AS c"))
                    .hasStackTraceContaining("AUTHENTICATION_FAILED");
        }
    }

    @Test
    void onboardWithoutCreateSchemaFailsLoudlyBeforeProvisioning() throws Exception {
        // A typo'd --database must fail before any statement runs — not silently provision a
        // phantom database with the shared roles granted on it (issue #267).
        final var err = new ByteArrayOutputStream();
        final int code = ProvisioningCommand.run(
                new String[] {"onboard", "--admin-url", endpoint(), "--database", "ript1de",
                        "--tenant", "phantom", "--org", "phantom-eu",
                        "--writer-secret", "wP", "--reader-secret", "rP"},
                discard(), new PrintStream(err, true, StandardCharsets.UTF_8));

        Assertions.assertThat(code).isEqualTo(1);
        Assertions.assertThat(err.toString(StandardCharsets.UTF_8)).contains("--create-schema");
        try (var admin = new Client.Builder()
                .addEndpoint(endpoint()).setUsername("default").setPassword("").build()) {
            Assertions.assertThat(admin.queryAll("EXISTS DATABASE `ript1de`")
                    .getFirst().getLong("result")).isZero();
            // A prefix match, not the exact name: the account the aborted run would have created
            // is `writer_phantom@ript1de`, so pinning the bare name would have stopped catching
            // the leak the moment #649 qualified it.
            Assertions.assertThat(admin.queryAll(
                            "SELECT count() AS c FROM system.users WHERE name LIKE 'writer_phantom%'")
                    .getFirst().getLong("c")).isZero();
        }
    }

    @Test
    void onboardAcceptsAnAdminProvisionedSchemaWithoutTheFlag() throws Exception {
        // Brownfield/upgrade path: a manage-mode collector owns the schema first; a plain onboard
        // (no --create-schema) must accept it — this is also the documented clustered-deployment
        // shape, where the table is pre-created admin-side.
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("default"));
        config.setDatabase("brown");
        config.setManageSchema(true);
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS).start();

        final int code = ProvisioningCommand.run(
                new String[] {"onboard", "--admin-url", endpoint(), "--database", "brown",
                        "--tenant", "browny", "--org", "brown-eu",
                        "--writer-secret", "wB", "--reader-secret", "rB"},
                discard(), discard());
        Assertions.assertThat(code).isZero();
    }

    @Test
    void offboardRevokesAccessOnlyWithYes() throws Exception {
        final String tempReader = ProvisioningDdl.readerUser("temp", DATABASE);
        Assertions.assertThat(onboard("temp", "temp-eu", "wT", "rT")).isZero();
        try (var reader = rawClient(tempReader, "rT")) {
            Assertions.assertThat(reader.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // Without --yes it refuses and changes nothing.
        Assertions.assertThat(ProvisioningCommand.run(
                new String[] {"offboard", "--admin-url", endpoint(), "--database", DATABASE, "--tenant", "temp"},
                discard(), discard())).isEqualTo(2);
        try (var reader = rawClient(tempReader, "rT")) {
            Assertions.assertThat(reader.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // With --yes the user is gone and can no longer authenticate.
        Assertions.assertThat(ProvisioningCommand.run(
                new String[] {"offboard", "--admin-url", endpoint(), "--database", DATABASE, "--tenant", "temp", "--yes"},
                discard(), discard())).isZero();
        try (var reader = rawClient(tempReader, "rT")) {
            Assertions.assertThatThrownBy(() -> reader.queryAll("SELECT 1 AS c"))
                    .hasStackTraceContaining(ClickhouseServerErrors.AUTHENTICATION_FAILED_MESSAGE_PREFIX)
                    .hasStackTraceContaining(tempReader);
        }
    }

    /**
     * The same tenant id in two databases on one server is two independent tenants (#649).
     *
     * <p>Two things used to go wrong at once, and both are asserted here as behaviour rather than as
     * a name. The users carried no database, so the second onboarding's {@code ALTER USER} rotated
     * the first one's password out from under a running collector. And the {@code flow_writer} role
     * carried none either, so every writer on the instance held {@code INSERT} on every provisioned
     * database's {@code flows} and rollups.</p>
     *
     * <p>The refusal has to be {@code ACCESS_DENIED}, not {@code VIOLATED_CONSTRAINT}: the
     * {@code tenant_pinned} CHECK passes here, because both databases genuinely carry the tenant id
     * this credential is pinned to. Qualifying only the user names would have left that the sole
     * barrier, and it does not hold.</p>
     */
    @Test
    void aTenantOnboardedIntoTwoDatabasesGetsTwoIsolatedAccounts() throws Exception {
        Assertions.assertThat(onboardDual("iso_a", "pwA")).isZero();
        Assertions.assertThat(onboardDual("iso_b", "pwB")).isZero();

        final String writerA = ProvisioningDdl.writerUser("dual", "iso_a");
        final String writerB = ProvisioningDdl.writerUser("dual", "iso_b");
        Assertions.assertThat(writerA).isNotEqualTo(writerB);

        // The second onboarding did not rotate the first one's password.
        try (var first = rawClientOn("iso_a", writerA, "pwA")) {
            Assertions.assertThat(first.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        try (var second = rawClientOn("iso_b", writerB, "pwB")) {
            // Its own database still works, so the refusals below are about the boundary and not
            // about a writer that cannot write at all.
            second.execute("INSERT INTO " + FlowsSchema.qualifiedFlows("iso_b")
                    + " (tenant, organisation, timestamp) VALUES ('dual', 'dual-eu', now())").get();

            Assertions.assertThatThrownBy(() -> second.execute("INSERT INTO "
                            + FlowsSchema.qualifiedFlows("iso_a")
                            + " (tenant, organisation, timestamp) VALUES ('dual', 'dual-eu', now())").get())
                    .as("the writer of one database must not reach another database's flows")
                    .hasStackTraceContaining(ClickhouseServerErrors.ACCESS_DENIED_MESSAGE_PREFIX)
                    .hasStackTraceContaining("ACCESS_DENIED");

            // And the rollup targets, which no CHECK constraint covers at all: they are fed by
            // materialized views, so a direct INSERT there is stopped by the grant or by nothing.
            final String rollup = FlowsSchema.qualifiedRollup("iso_a", FlowsSchema.rollupTableNames().getFirst());
            Assertions.assertThatThrownBy(() -> second.execute("INSERT INTO " + rollup
                            + " (tenant, organisation, timestamp) VALUES ('dual', 'dual-eu', now())").get())
                    .as("nor another database's rollup targets")
                    .hasStackTraceContaining(ClickhouseServerErrors.ACCESS_DENIED_MESSAGE_PREFIX)
                    .hasStackTraceContaining("ACCESS_DENIED");
        }

        // The first database's row is the only one in it: nothing crossed.
        try (var admin = adminClient()) {
            Assertions.assertThat(admin.queryAll("SELECT count() AS c FROM " + FlowsSchema.qualifiedFlows("iso_a"))
                    .getFirst().getLong("c")).isZero();
            Assertions.assertThat(admin.queryAll("SELECT count() AS c FROM " + FlowsSchema.qualifiedFlows("iso_b"))
                    .getFirst().getLong("c")).isEqualTo(1);
        }
    }

    /**
     * Offboarding an instance provisioned under the pre-#649 naming really revokes it.
     *
     * <p>The rename is what makes this a test rather than a tautology: the credential that
     * authenticates on such an instance is the unqualified {@code writer_old}, and an
     * {@code offboard} that dropped only {@code writer_old@upg} would have printed its "offboarded"
     * line over a credential that still works. The fixture is built by hand precisely because no
     * current code path can produce that state any more.</p>
     */
    @Test
    void offboardRevokesAnInstanceProvisionedUnderTheOldNaming() throws Exception {
        try (var admin = adminClient()) {
            admin.execute(FlowsSchema.createDatabase("upg")).get();
            admin.execute(FlowsSchema.createFlowsTable("upg")).get();
            // Exactly what riptide emitted before the rename: instance-wide roles, unqualified users.
            admin.execute("CREATE ROLE IF NOT EXISTS flow_writer").get();
            admin.execute("GRANT INSERT ON " + FlowsSchema.qualifiedFlows("upg") + " TO flow_writer").get();
            admin.execute("CREATE USER writer_old IDENTIFIED WITH sha256_password BY 'legacyW'"
                    + " SETTINGS SQL_tenant = 'old' CONST, SQL_org = 'old-eu' CONST").get();
            admin.execute("GRANT flow_writer TO writer_old").get();
            admin.execute("CREATE USER bi_old IDENTIFIED WITH sha256_password BY 'legacyR'").get();

            try (var legacy = rawClientOn("upg", "writer_old", "legacyW")) {
                Assertions.assertThat(legacy.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
            }

            Assertions.assertThat(ProvisioningCommand.run(
                    new String[] {"offboard", "--admin-url", endpoint(), "--database", "upg",
                            "--tenant", "old", "--yes"},
                    discard(), discard())).isZero();

            try (var stale = rawClientOn("upg", "writer_old", "legacyW")) {
                Assertions.assertThatThrownBy(() -> stale.queryAll("SELECT 1 AS c"))
                        .as("the credential the operator was told is revoked must stop authenticating")
                        .hasStackTraceContaining(ClickhouseServerErrors.AUTHENTICATION_FAILED_MESSAGE_PREFIX);
            }
            Assertions.assertThat(admin.queryAll("SELECT count() AS c FROM system.users"
                            + " WHERE name IN ('writer_old', 'bi_old')").getFirst().getLong("c"))
                    .isZero();
            // The legacy role stays: it is instance-wide and may still be granted to another
            // tenant's legacy user, so dropping it here would revoke a tenant nobody offboarded.
            Assertions.assertThat(admin.queryAll(
                            "SELECT count() AS c FROM system.roles WHERE name = 'flow_writer'")
                    .getFirst().getLong("c")).isEqualTo(1);
        }
    }

    /**
     * A live pre-#649 account is reported on a successful onboard, and not dropped.
     *
     * <p>Dropping it here would take a rolling upgrade's ingest down: the tenant's collector is
     * still authenticating as it until the operator pastes the new stanza. Saying nothing would
     * leave the cross-database write this change closes wide open for that account, discovered by
     * an operator rather than by CI.</p>
     */
    @Test
    void onboardWarnsAboutALiveLegacyAccountWithoutDroppingIt() throws Exception {
        try (var admin = adminClient()) {
            admin.execute("CREATE USER writer_warn IDENTIFIED WITH sha256_password BY 'legacyW'").get();

            final var err = new ByteArrayOutputStream();
            Assertions.assertThat(ProvisioningCommand.run(
                    onboardArgs("warn", "warn-eu", "wW", "rW"),
                    discard(), new PrintStream(err, true, StandardCharsets.UTF_8))).isZero();

            Assertions.assertThat(err.toString(StandardCharsets.UTF_8))
                    .contains("writer_warn")
                    .contains("DROP USER");

            // Named, not dropped — it still authenticates.
            try (var legacy = rawClient("writer_warn", "legacyW")) {
                Assertions.assertThat(legacy.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
            }
        }
    }

    /** Onboard tenant {@code dual} into {@code database} with its own writer secret. */
    private static int onboardDual(final String database, final String writerPw) {
        return ProvisioningCommand.run(new String[] {
                "onboard", "--admin-url", endpoint(), "--database", database, "--create-schema",
                "--tenant", "dual", "--org", "dual-eu",
                "--writer-secret", writerPw, "--reader-secret", "r" + writerPw}, discard(), discard());
    }

    private static Client adminClient() {
        return new Client.Builder().addEndpoint(endpoint()).setUsername("default").setPassword("").build();
    }

    @Test
    void onboardUpgradesAPreRollupPreGeoDatabaseInPlace() throws Exception {
        // A database provisioned before the geo columns and the rollups existed: the flows table is
        // perfectly good, so the schema check passes while the rollups are simply absent. This is
        // the upgrade path for every deployment onboarded before this feature landed.
        try (var admin = new Client.Builder()
                .addEndpoint(endpoint()).setUsername("default").setPassword("").build()) {
            admin.execute(FlowsSchema.createDatabase("legacy")).get();
            admin.execute(FlowsSchema.createFlowsTable("legacy")).get();
            for (final String column : FlowsSchema.additiveColumnNames()) {
                admin.execute("ALTER TABLE legacy.flows DROP COLUMN " + column).get();
            }

            // Without --create-schema it must refuse, and say why in terms the operator can act on.
            final var err = new ByteArrayOutputStream();
            Assertions.assertThat(ProvisioningCommand.run(legacyOnboardArgs(false),
                    discard(), new PrintStream(err, true, StandardCharsets.UTF_8))).isEqualTo(1);
            Assertions.assertThat(err.toString(StandardCharsets.UTF_8))
                    .contains("rollup")
                    .contains("--create-schema");

            Assertions.assertThat(ProvisioningCommand.run(legacyOnboardArgs(true), discard(), discard())).isZero();
            for (final String rollup : FlowsSchema.rollupTableNames()) {
                Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollup("legacy", rollup))).isTrue();
                Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollupView("legacy", rollup))).isTrue();
            }

            // Half-provisioned is also detected: targets present, views dropped. An interrupted
            // bootstrap must not read as healthy, or the rollups stay silently empty.
            for (final String rollup : FlowsSchema.rollupTableNames()) {
                admin.execute("DROP VIEW " + FlowsSchema.qualifiedRollupView("legacy", rollup)).get();
            }
            Assertions.assertThat(ProvisioningCommand.run(legacyOnboardArgs(false), discard(), discard()))
                    .isEqualTo(1);
            Assertions.assertThat(ProvisioningCommand.run(legacyOnboardArgs(true), discard(), discard()))
                    .isZero();
            for (final String rollup : FlowsSchema.rollupTableNames()) {
                Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollupView("legacy", rollup))).isTrue();
            }
        }
    }

    /**
     * A rollup riptide has refused to repair must cost that rollup, not the tenant — and must not
     * get a view built for it.
     *
     * <p>Three rounds of fixes to this path went in without a test: no test anywhere constructed a
     * refused rollup and then ran {@code onboard}, so reverting both of them left every provisioning
     * suite green. The state is the one the Code 36 refusal exists for: an operator has hand-added
     * {@code samplingInterval}, so the column is present but outside the sorting key and no
     * {@code ALTER} can move it there.</p>
     */
    @Test
    void onboardLeavesARefusedRollupAloneWithoutFailingTheTenant() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        try (var admin = new Client.Builder()
                .addEndpoint(endpoint()).setUsername("default").setPassword("").build()) {
            admin.execute(FlowsSchema.createDatabase("refuse")).get();
            admin.execute(FlowsSchema.createFlowsTable("refuse")).get();
            Assertions.assertThat(ProvisioningCommand.run(refuseOnboardArgs(true), discard(), discard())).isZero();

            // Hand-add the column and put the key back where an older version left it: present as a
            // column, absent from the sorting key.
            final String target = FlowsSchema.qualifiedRollup("refuse", rollup);
            admin.execute("DROP VIEW " + FlowsSchema.qualifiedRollupView("refuse", rollup)).get();
            admin.execute("DROP TABLE " + target).get();
            admin.execute("CREATE TABLE " + target + " ("
                    + "tenant String, organisation String, timestamp DateTime('UTC'), zone String,"
                    + " application LowCardinality(String), protocol UInt8, samplingInterval Float64,"
                    + " bytes UInt64, packets UInt64, flowCount UInt64,"
                    + " bytesIn UInt64, bytesOut UInt64, packetsIn UInt64, packetsOut UInt64)"
                    + " ENGINE = SummingMergeTree()"
                    + " PRIMARY KEY (tenant, organisation, timestamp, zone, application, protocol)"
                    + " ORDER BY (tenant, organisation, timestamp, zone, application, protocol)"
                    + " PARTITION BY toYYYYMM(timestamp)").get();

            // A routine re-run — the password rotation the docs describe — must still succeed.
            Assertions.assertThat(ProvisioningCommand.run(refuseOnboardArgs(false), discard(), discard()))
                    .as("a refused rollup must not make every later onboard demand --create-schema")
                    .isZero();

            // Now force the create-views branch to actually run, by making another rollup genuinely
            // missing. Without this the whole branch is skipped and the assertion below would hold
            // for a reason that has nothing to do with the refusal — which is how the first version
            // of this test passed while proving nothing.
            final String healthy = FlowsSchema.rollupTableNames().get(1);
            admin.execute("DROP VIEW " + FlowsSchema.qualifiedRollupView("refuse", healthy)).get();
            Assertions.assertThat(ProvisioningCommand.run(refuseOnboardArgs(true), discard(), discard()))
                    .isZero();
            Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollupView("refuse", healthy)))
                    .as("the missing view of a healthy rollup is restored")
                    .isTrue();

            Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollupView("refuse", rollup)))
                    .as("building the writer for a rollup just refused would write the rate into a"
                            + " non-key column of a SummingMergeTree, which sums it across merges")
                    .isFalse();
            for (final String other : FlowsSchema.rollupTableNames()) {
                if (!other.equals(rollup)) {
                    Assertions.assertThat(exists(admin, FlowsSchema.qualifiedRollupView("refuse", other)))
                            .as("%s was not refused and must be untouched", other)
                            .isTrue();
                }
            }
        }
    }

    /**
     * Re-running {@code onboard} against a current database issues no rollup {@code ALTER}.
     *
     * <p>Required by {@code onboard-schema-bootstrap}: a password rotation must not rewrite schema.
     * Until the view repair was planned from the views' own SELECT it re-pointed every present view
     * unconditionally, so a no-op run emitted four {@code MODIFY QUERY} statements.</p>
     */
    @Test
    void reRunningOnboardAgainstACurrentDatabaseIssuesNoRollupAlter() throws Exception {
        try (var admin = new Client.Builder()
                .addEndpoint(endpoint()).setUsername("default").setPassword("").build()) {
            admin.execute(FlowsSchema.createDatabase("noop")).get();
            admin.execute(FlowsSchema.createFlowsTable("noop")).get();
            Assertions.assertThat(ProvisioningCommand.run(noopOnboardArgs(true), discard(), discard())).isZero();

            final long before = alterCountOn(admin, "noop");
            Assertions.assertThat(ProvisioningCommand.run(noopOnboardArgs(false), discard(), discard())).isZero();

            Assertions.assertThat(alterCountOn(admin, "noop") - before)
                    .as("a re-run against an already-correct database must issue no rollup ALTER;"
                            + " saw: %s", alterTextOn(admin, "noop"))
                    .isZero();
        }
    }

    /** Rollup ALTERs recorded in the query log for one database. */
    private static long alterCountOn(final Client admin, final String database) throws Exception {
        admin.execute("SYSTEM FLUSH LOGS").get();
        try (var records = admin.queryRecords("SELECT count() AS c FROM system.query_log"
                + " WHERE type = 'QueryFinish' AND query ILIKE '%ALTER TABLE%" + database + ".flows_by%'"
                + " AND query NOT ILIKE '%system.query_log%'").get()) {
            for (final var record : records) {
                return record.getLong("c");
            }
        }
        return 0;
    }

    /** The rollup ALTER statements themselves, so a failure says which one fired. */
    private static String alterTextOn(final Client admin, final String database) throws Exception {
        final var seen = new ArrayList<String>();
        try (var records = admin.queryRecords("SELECT query AS q FROM system.query_log"
                + " WHERE type = 'QueryFinish' AND query ILIKE '%ALTER TABLE%" + database
                + ".flows_by%' AND query NOT ILIKE '%system.query_log%'"
                + " ORDER BY event_time DESC LIMIT 5").get()) {
            records.forEach(record -> seen.add(record.getString("q").replaceAll("\\s+", " ")));
        }
        return String.join(" | ", seen);
    }

    private static String[] refuseOnboardArgs(final boolean createSchema) {
        return onboardArgsFor("refuse", "ref", createSchema);
    }

    private static String[] noopOnboardArgs(final boolean createSchema) {
        return onboardArgsFor("noop", "noo", createSchema);
    }

    private static String[] onboardArgsFor(final String database, final String tenant,
            final boolean createSchema) {
        final var args = new ArrayList<>(List.of(
                "onboard", "--admin-url", endpoint(), "--database", database,
                "--tenant", tenant, "--org", tenant + "-eu",
                "--writer-secret", "w" + tenant, "--reader-secret", "r" + tenant));
        if (createSchema) {
            args.add("--create-schema");
        }
        return args.toArray(String[]::new);
    }

    private static int onboard(final String tenant, final String org, final String writerPw, final String readerPw) {
        return ProvisioningCommand.run(onboardArgs(tenant, org, writerPw, readerPw), discard(), discard());
    }

    private static String[] legacyOnboardArgs(final boolean createSchema) {
        final var args = new ArrayList<>(List.of(
                "onboard", "--admin-url", endpoint(), "--database", "legacy",
                "--tenant", "leg", "--org", "leg-eu", "--writer-secret", "wL", "--reader-secret", "rL"));
        if (createSchema) {
            args.add("--create-schema");
        }
        return args.toArray(String[]::new);
    }

    private static boolean exists(final Client admin, final String qualifiedName) {
        return admin.queryAll("EXISTS TABLE " + qualifiedName).getFirst().getLong("result") == 1;
    }

    private static String[] onboardArgs(final String tenant, final String org, final String writerPw, final String readerPw) {
        // --create-schema: nothing is pre-created, so the first onboard bootstraps database + table.
        return new String[] {
                "onboard", "--admin-url", endpoint(), "--database", DATABASE, "--create-schema",
                "--tenant", tenant, "--org", org, "--writer-secret", writerPw, "--reader-secret", readerPw};
    }

    private static ClickhouseRepository writerRepository(final String tenant, final String password) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of(ProvisioningDdl.writerUser(tenant, DATABASE)));
        config.setPassword(SecretRef.of(password));
        config.setDatabase(DATABASE);
        config.setManageSchema(false);
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repository.start();
        return repository;
    }

    private static Client rawClient(final String user, final String password) {
        return rawClientOn(DATABASE, user, password);
    }

    private static Client rawClientOn(final String database, final String user, final String password) {
        return new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername(user)
                .setPassword(password)
                .setDefaultDatabase(database)
                .build();
    }

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
