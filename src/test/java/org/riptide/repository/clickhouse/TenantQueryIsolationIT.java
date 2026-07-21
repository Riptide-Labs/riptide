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

import java.util.List;

import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * The read side of multi-tenant isolation: a per-tenant BI/query credential is a real boundary,
 * not just a filter. This proves the {@code onboard_tenant} runbook's reader recipe against a real
 * ClickHouse server, the read-side counterpart to {@link TenantWriteBarrierIT}.
 *
 * <p>An admin provisions a {@code bi_acme} user with {@code readonly = 2}, {@code allow_ddl = 0}, a
 * {@code SELECT} grant on the flows table plus the catalog tables a query builder needs, and a row
 * policy scoping it to {@code tenant = 'acme'}. The assertions:
 * <ul>
 *   <li>it reads only its own tenant's rows (row policy);</li>
 *   <li>as provisioned (no write grant) it cannot {@code INSERT} — rejected with ACCESS_DENIED,
 *       no row lands;</li>
 *   <li>as provisioned (no ALTER grant) it cannot DDL ({@code ALTER … ADD COLUMN}) — rejected with
 *       ACCESS_DENIED, the schema is unchanged;</li>
 *   <li>it can read {@code system.columns} for {@code flows} (the Grafana query builder works).</li>
 * </ul>
 *
 * <p>The recipe hardens the reader in two independent layers — the missing write grants AND
 * {@code readonly}/{@code allow_ddl} — so a stray future grant does not silently open the boundary.
 * The first three assertions above exercise the grant layer (it fires first). A separate probe
 * ({@link #readonlyBlocksAGrantedWrite()}) proves {@code readonly} itself blocks a write even when
 * the grant is present, so both layers are covered.
 *
 * <p>The read side needs no custom settings, so — unlike the write-barrier IT — no {@code SQL_}
 * config is mounted.
 */
@Testcontainers
public class TenantQueryIsolationIT {

    private static final String DATABASE = "queryiso";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
            // access management lets the default (admin) user CREATE USER / ROW POLICY.
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    /** Admin client (default user, access management) for provisioning and query-back. */
    private static Client admin;

    @BeforeAll
    static void provision() throws Exception {
        admin = new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername("default")
                .setPassword("")
                .build();

        admin.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE).get();

        // Create the flows table with riptide's own manage-mode DDL, as an admin would, then seed
        // two tenants' rows. The admin writer has no CONST pinning, so it can land any tenant.
        final var seeder = new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), adminConfig(), RESOLVERS);
        seeder.start();
        seeder.persist(List.of(
                flow("acme", "acme-eu", 27001),
                flow("other", "other-eu", 27002)));

        // The hardened BI credential: read-only, no DDL, scoped to its own tenant by a row policy,
        // with catalog access so a query builder (Grafana) can inspect the schema. readonly=2
        // blocks writes and DDL while still tolerating the read-only settings the HTTP client sends
        // per query (readonly=1 would reject those and break the connection).
        admin.execute("CREATE USER bi_acme IDENTIFIED WITH no_password "
                + "SETTINGS readonly = 2, allow_ddl = 0").get();
        admin.execute("GRANT SELECT ON " + DATABASE + ".flows TO bi_acme").get();
        admin.execute("GRANT SELECT ON system.databases TO bi_acme").get();
        admin.execute("GRANT SELECT ON system.tables TO bi_acme").get();
        admin.execute("GRANT SELECT ON system.columns TO bi_acme").get();
        admin.execute("CREATE ROW POLICY acme_bi ON " + DATABASE + ".flows "
                + "FOR SELECT USING tenant = 'acme' TO bi_acme").get();

        // A probe with an INSERT grant but readonly = 2: proves the readonly layer blocks writes
        // independently of the grant layer, so it is genuinely load-bearing (a stray future write
        // grant would not silently open the boundary).
        admin.execute("CREATE USER probe_readonly IDENTIFIED WITH no_password SETTINGS readonly = 2").get();
        admin.execute("GRANT INSERT ON " + DATABASE + ".flows TO probe_readonly").get();
    }

    @Test
    void biUserReadsOnlyItsTenant() throws Exception {
        // Admin sees both seeded rows.
        Assertions.assertThat(count(admin, "SELECT count() AS c FROM " + DATABASE + ".flows "
                + "WHERE srcPort IN (27001, 27002)")).isEqualTo(2);

        try (var bi = biClient()) {
            final var rows = bi.queryAll(
                    "SELECT srcPort, tenant FROM " + DATABASE + ".flows WHERE srcPort IN (27001, 27002)");
            Assertions.assertThat(rows).hasSize(1);
            Assertions.assertThat(rows.getFirst().getInteger("srcPort")).isEqualTo(27001);
            Assertions.assertThat(rows.getFirst().getString("tenant")).isEqualTo("acme");
        }
    }

    @Test
    void biUserCannotWrite() throws Exception {
        try (var bi = biClient()) {
            // The BI user has no INSERT grant, so the write is rejected with ACCESS_DENIED (the
            // grant check fires before readonly). Behavioural backstop: no row lands.
            Assertions.assertThatThrownBy(
                            () -> bi.execute("INSERT INTO " + DATABASE + ".flows (tenant) VALUES ('acme')").get())
                    .hasStackTraceContaining("ACCESS_DENIED");
        }
        Assertions.assertThat(count(admin, "SELECT count() AS c FROM " + DATABASE + ".flows WHERE srcPort = 0"))
                .isZero();
    }

    @Test
    void biUserCannotChangeSchema() throws Exception {
        try (var bi = biClient()) {
            // No ALTER grant → ACCESS_DENIED (allow_ddl=0 is a second layer). Assert the specific
            // rejection, not just any exception, so a malformed statement can't pass vacuously.
            Assertions.assertThatThrownBy(
                            () -> bi.execute("ALTER TABLE " + DATABASE + ".flows ADD COLUMN hacked String").get())
                    .hasStackTraceContaining("ACCESS_DENIED");
        }
        // The schema is unchanged — the injected column never appeared.
        Assertions.assertThat(count(admin, "SELECT count() AS c FROM system.columns "
                + "WHERE database = '" + DATABASE + "' AND table = 'flows' AND name = 'hacked'")).isZero();
    }

    @Test
    void readonlyBlocksAGrantedWrite() throws Exception {
        // probe_readonly *has* the INSERT grant, so only readonly = 2 can stop it: proves the
        // readonly layer is load-bearing, not redundant with the missing grant.
        final var probe = new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername("probe_readonly")
                .setPassword("")
                .setDefaultDatabase(DATABASE)
                .build();
        try (probe) {
            Assertions.assertThatThrownBy(
                            () -> probe.execute("INSERT INTO " + DATABASE + ".flows (tenant) VALUES ('acme')").get())
                    .hasStackTraceContaining("READONLY");
        }
    }

    @Test
    void biUserCanReadCatalog() throws Exception {
        try (var bi = biClient()) {
            // The Grafana query builder inspects columns via system.columns.
            final var columns = bi.queryAll("SELECT name FROM system.columns "
                    + "WHERE database = '" + DATABASE + "' AND table = 'flows'");
            Assertions.assertThat(columns).isNotEmpty();
        }
    }

    private static long count(final Client client, final String sql) throws Exception {
        return client.queryAll(sql).getFirst().getLong("c");
    }

    private static Client biClient() {
        return new Client.Builder()
                .addEndpoint(endpoint())
                .setUsername("bi_acme")
                .setPassword("")
                .setDefaultDatabase(DATABASE)
                .build();
    }

    private static ClickhouseConfig adminConfig() {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("default"));
        config.setDatabase(DATABASE);
        config.setManageSchema(true);
        // The isolation assertions read the seeded rows back immediately; async coalescing is
        // covered by its own test in ClickhouseRepositoryIT.
        config.setAsyncInserts(false);
        return config;
    }

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
