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
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
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
 * the full isolation matrix — honest write persists, cross-tenant write is rejected (469), the
 * reader sees only its tenant and cannot write/DDL — and {@code offboard} must revoke access.
 *
 * <p>The server needs {@code custom_settings_prefixes: SQL_} for the CHECK barrier the onboarding
 * recipe installs; it is mounted from the classpath, as in {@link TenantWriteBarrierIT}.
 */
@Testcontainers
public class TenantOnboardingIT {

    private static final String DATABASE = "onb";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
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
                .hasStackTraceContaining("469")
                .hasStackTraceContaining("VIOLATED_CONSTRAINT");

        // The reader sees only its own tenant (row policy) and cannot write or DDL (readonly role).
        try (var reader = rawClient("bi_acme", "rA")) {
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
                .contains("riptide.clickhouse.username=writer_cfg")
                .contains("riptide.clickhouse.password=wC")
                .contains("riptide.identity.tenant=cfg")
                .contains("riptide.identity.organisation=cfg-eu");

        // Re-running is a no-op that still succeeds (IF NOT EXISTS everywhere).
        Assertions.assertThat(onboard("cfg", "cfg-eu", "wC", "rC")).isZero();
    }

    @Test
    void reonboardRotatesTheWriterPassword() throws Exception {
        Assertions.assertThat(onboard("rot", "rot-eu", "pw1", "rr1")).isZero();
        try (var writer = rawClient("writer_rot", "pw1")) {
            Assertions.assertThat(writer.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // Re-onboard with a rotated writer secret: the new password must take effect...
        Assertions.assertThat(onboard("rot", "rot-eu", "pw2", "rr1")).isZero();
        try (var writer = rawClient("writer_rot", "pw2")) {
            Assertions.assertThat(writer.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }
        // ...and the old one must stop working.
        try (var stale = rawClient("writer_rot", "pw1")) {
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
            Assertions.assertThat(admin.queryAll(
                            "SELECT count() AS c FROM system.users WHERE name = 'writer_phantom'")
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
        Assertions.assertThat(onboard("temp", "temp-eu", "wT", "rT")).isZero();
        try (var reader = rawClient("bi_temp", "rT")) {
            Assertions.assertThat(reader.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // Without --yes it refuses and changes nothing.
        Assertions.assertThat(ProvisioningCommand.run(
                new String[] {"offboard", "--admin-url", endpoint(), "--database", DATABASE, "--tenant", "temp"},
                discard(), discard())).isEqualTo(2);
        try (var reader = rawClient("bi_temp", "rT")) {
            Assertions.assertThat(reader.queryAll("SELECT 1 AS c").getFirst().getLong("c")).isEqualTo(1);
        }

        // With --yes the user is gone and can no longer authenticate.
        Assertions.assertThat(ProvisioningCommand.run(
                new String[] {"offboard", "--admin-url", endpoint(), "--database", DATABASE, "--tenant", "temp", "--yes"},
                discard(), discard())).isZero();
        try (var reader = rawClient("bi_temp", "rT")) {
            Assertions.assertThatThrownBy(() -> reader.queryAll("SELECT 1 AS c"))
                    .hasStackTraceContaining("bi_temp");
        }
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
        config.setUsername(SecretRef.of("writer_" + tenant));
        config.setPassword(SecretRef.of(password));
        config.setDatabase(DATABASE);
        config.setManageSchema(false);
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repository.start();
        return repository;
    }

    private static Client rawClient(final String user, final String password) {
        return new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername(user)
                .setPassword(password)
                .setDefaultDatabase(DATABASE)
                .build();
    }

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
