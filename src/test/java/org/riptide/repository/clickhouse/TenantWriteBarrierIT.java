/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * The multi-tenant write barrier, proven end to end against a real ClickHouse server.
 *
 * <p>An admin provisions the barrier (phase-2 validate mode): a {@code flows} table carrying
 * {@code CHECK tenant = getSetting('SQL_tenant')} / {@code CHECK organisation = getSetting('SQL_org')},
 * a per-{@code (tenant, org)} writer whose {@code SQL_tenant}/{@code SQL_org} settings are pinned
 * {@code CONST}, and a row policy scoping a readonly reader to its own tenant. riptide only stamps
 * its configured tenant/org and connects as the scoped writer — it never emits a CHECK constraint.
 *
 * <p>The attack matrix asserted here:
 * <ul>
 *   <li>honest write (riptide identity matches the credential) persists;</li>
 *   <li>cross-tenant write (a tampered identity) is rejected with error 469 (VIOLATED_CONSTRAINT);</li>
 *   <li>an attempt to override the pinned CONST setting is rejected with error 452
 *       (SETTING_CONSTRAINT_VIOLATION);</li>
 *   <li>a reader sees only its own tenant's rows (row policy).</li>
 * </ul>
 *
 * <p>The server needs {@code custom_settings_prefixes: SQL_}, which is config-only (not an env
 * var); it is mounted from a {@code config.d} snippet on the classpath.
 */
@Testcontainers
public class TenantWriteBarrierIT {

    private static final String DATABASE = "barrier";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
            // access management lets the default (admin) user CREATE USER / ROW POLICY.
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("clickhouse/custom-settings.xml"),
                    "/etc/clickhouse-server/config.d/custom-settings.xml")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    /** Admin client (default user, access management) for provisioning and query-back. */
    private static Client admin;

    @BeforeAll
    static void provision() throws Exception {
        final String endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);

        admin = new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername("default")
                .setPassword("")
                .build();

        admin.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE).get();

        // Create the base flows table with riptide's own manage-mode DDL, as an admin would.
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), adminConfig(true), RESOLVERS).start();

        // The barrier: per-row CHECK constraints tying tenant/org to the writer's CONST settings.
        // Added by ALTER after creation — the expression is only evaluated on INSERT, so no
        // SQL_tenant needs to be defined in this admin DDL session.
        admin.execute("ALTER TABLE " + DATABASE + ".flows "
                + "ADD CONSTRAINT tenant_pinned CHECK tenant = getSetting('SQL_tenant')").get();
        admin.execute("ALTER TABLE " + DATABASE + ".flows "
                + "ADD CONSTRAINT org_pinned CHECK organisation = getSetting('SQL_org')").get();

        // Per-(tenant, org) writers: their SQL_tenant/SQL_org are pinned CONST (unchangeable).
        admin.execute("CREATE USER writer_acme IDENTIFIED WITH no_password "
                + "SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST").get();
        admin.execute("GRANT INSERT ON " + DATABASE + ".flows TO writer_acme").get();
        // Writers also read flows (materialized views run as the inserting user), so they need
        // SELECT and their own row policy — mirroring what onboard provisions.
        admin.execute("GRANT SELECT ON " + DATABASE + ".flows TO writer_acme").get();
        admin.execute("CREATE ROW POLICY acme_write ON " + DATABASE + ".flows "
                + "FOR SELECT USING tenant = 'acme' TO writer_acme").get();

        admin.execute("CREATE USER writer_other IDENTIFIED WITH no_password "
                + "SETTINGS SQL_tenant = 'other' CONST, SQL_org = 'other-eu' CONST").get();
        admin.execute("GRANT INSERT ON " + DATABASE + ".flows TO writer_other").get();
        admin.execute("GRANT SELECT ON " + DATABASE + ".flows TO writer_other").get();
        admin.execute("CREATE ROW POLICY other_write ON " + DATABASE + ".flows "
                + "FOR SELECT USING tenant = 'other' TO writer_other").get();

        // Readonly reader for tenant acme, isolated to its own rows by a row policy.
        admin.execute("CREATE USER reader_acme IDENTIFIED WITH no_password").get();
        admin.execute("GRANT SELECT ON " + DATABASE + ".flows TO reader_acme").get();
        admin.execute("CREATE ROW POLICY acme_read ON " + DATABASE + ".flows "
                + "FOR SELECT USING tenant = 'acme' TO reader_acme").get();
    }

    @Test
    void honestWritePersists() throws Exception {
        final var writer = writerRepository("writer_acme");
        writer.persist(List.of(
                flow("acme", "acme-eu", 21001),
                flow("acme", "acme-eu", 21002)));

        final long count = admin.queryAll(
                        "SELECT count() AS c FROM " + DATABASE + ".flows WHERE srcPort IN (21001, 21002)")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isEqualTo(2);
    }

    @Test
    void crossTenantWriteRejectedWith469() {
        final var writer = writerRepository("writer_acme");

        // The collector's config lies (tenant=evil) but its credential's CONST setting is 'acme':
        // ClickHouse rejects the row. This is the compromised-collector attack.
        // Observed: Code: 469. DB::Exception: Constraint `tenant_pinned` for table barrier.flows
        // is violated at row 1. Expression: (tenant = getSetting('SQL_tenant')). Column values:
        // tenant = 'evil'. (VIOLATED_CONSTRAINT)
        Assertions.assertThatThrownBy(() -> writer.persist(List.of(flow("evil", "acme-eu", 21003))))
                .hasStackTraceContaining("469")
                .hasStackTraceContaining("VIOLATED_CONSTRAINT");

        final long count = admin.queryAll(
                        "SELECT count() AS c FROM " + DATABASE + ".flows WHERE tenant = 'evil'")
                .getFirst().getLong("c");
        Assertions.assertThat(count).isZero();
    }

    @Test
    void constSettingOverrideRejectedWith452() {
        final var writerClient = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("writer_acme")
                .setPassword("")
                .setDefaultDatabase(DATABASE)
                .build();

        // Attempt to override the pinned CONST setting at query level → SETTING_CONSTRAINT_VIOLATION.
        // Observed: Code: 452. DB::Exception: Setting SQL_tenant should not be changed.
        // (SETTING_CONSTRAINT_VIOLATION)
        Assertions.assertThatThrownBy(
                        () -> writerClient.queryAll("SELECT getSetting('SQL_tenant') SETTINGS SQL_tenant = 'other'"))
                .hasStackTraceContaining("452")
                .hasStackTraceContaining("SETTING_CONSTRAINT_VIOLATION");
    }

    @Test
    void readerSeesOnlyItsTenant() throws Exception {
        // Two pinned writers land one row each; the reader must see only the acme row.
        writerRepository("writer_acme").persist(List.of(flow("acme", "acme-eu", 26001)));
        writerRepository("writer_other").persist(List.of(flow("other", "other-eu", 26002)));

        // Admin sees both.
        final long adminCount = admin.queryAll(
                        "SELECT count() AS c FROM " + DATABASE + ".flows WHERE srcPort IN (26001, 26002)")
                .getFirst().getLong("c");
        Assertions.assertThat(adminCount).isEqualTo(2);

        final var reader = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("reader_acme")
                .setPassword("")
                .setDefaultDatabase(DATABASE)
                .build();

        final var rows = reader.queryAll(
                "SELECT srcPort, tenant FROM " + DATABASE + ".flows WHERE srcPort IN (26001, 26002)");
        Assertions.assertThat(rows).hasSize(1);
        Assertions.assertThat(rows.getFirst().getInteger("srcPort")).isEqualTo(26001);
        Assertions.assertThat(rows.getFirst().getString("tenant")).isEqualTo("acme");
    }

    private static ClickhouseRepository writerRepository(final String username) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of(username));
        config.setDatabase(DATABASE);
        // Provisioned mode: riptide validates the admin-owned schema, never creates it.
        config.setManageSchema(false);
        final var repository = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        // Validate-mode start: describe the provisioned table and register the insert POJO.
        repository.start();
        return repository;
    }

    private static ClickhouseConfig adminConfig(final boolean manageSchema) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("default"));
        config.setDatabase(DATABASE);
        config.setManageSchema(manageSchema);
        return config;
    }
}
