/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.riptide.schema.FlowsSchema;

import java.util.List;

/**
 * The ClickHouse SQL for role-based tenant provisioning. Pure string builders — no I/O — so the
 * whole recipe is one auditable place and lifts cleanly into a future {@code riptide-admin} module.
 *
 * <p>The model puts every identical-per-tenant part into one-time objects ({@link #ensureShared})
 * — the {@code flows} schema, the {@code flow_writer}/{@code flow_reader} roles carrying the grants
 * and the reader hardening, and a single quota keyed by user — so {@link #onboardTenant} reduces to
 * the scoped users, two role grants, and one literal row policy. All statements are idempotent
 * ({@code IF NOT EXISTS} / {@code ALTER ROLE SETTINGS}), verified on ClickHouse 25.3.
 *
 * <p>Tenant/org names are validated ({@link TenantSpec}) to a safe charset; generated identifiers
 * are backtick-quoted and string literals are escaped, so neither can break out of the statement.
 */
public final class ProvisioningDdl {

    private ProvisioningDdl() {
    }

    /**
     * One-time shared objects: the database and {@code flows} table (so onboarding a fresh
     * provisioned deployment is self-sufficient — the collector only validates in
     * {@code manage-schema=false} mode), then the two roles, the reader hardening, the CHECK
     * barrier, and the quota. The schema DDL comes first because the {@code GRANT INSERT} and
     * {@code ALTER TABLE … ADD CONSTRAINT} below require the table to exist.
     */
    public static List<String> ensureShared(final String database, final long quotaBytes) {
        final String flows = ident(database) + ".flows";
        return List.of(
                FlowsSchema.createDatabase(database),
                FlowsSchema.createFlowsTable(database),
                "CREATE ROLE IF NOT EXISTS flow_writer",
                "GRANT INSERT ON " + flows + " TO flow_writer",
                "CREATE ROLE IF NOT EXISTS flow_reader",
                "GRANT SELECT ON " + flows + " TO flow_reader",
                "GRANT SELECT ON system.databases TO flow_reader",
                "GRANT SELECT ON system.tables TO flow_reader",
                "GRANT SELECT ON system.columns TO flow_reader",
                // readonly = 2 blocks writes and DDL while tolerating the read-only settings an HTTP
                // client sends per query (readonly = 1 would reject those and break the connection).
                "ALTER ROLE flow_reader SETTINGS readonly = 2, allow_ddl = 0",
                "ALTER TABLE " + flows
                        + " ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant')",
                "ALTER TABLE " + flows
                        + " ADD CONSTRAINT IF NOT EXISTS org_pinned CHECK organisation = getSetting('SQL_org')",
                "CREATE QUOTA IF NOT EXISTS flow_ingest FOR INTERVAL 1 hour MAX written_bytes = "
                        + quotaBytes + " KEYED BY user_name TO flow_writer");
    }

    /**
     * Per-tenant: the scoped writer/reader users, their role grants, and the literal row policy.
     * Each user is created if absent and then has its password reconciled with {@code ALTER USER}
     * — so re-running after a secret rotation updates the credential (a plain
     * {@code CREATE … IF NOT EXISTS} would silently keep the old password). {@code ALTER USER}
     * preserves the user's {@code CONST} settings and its row-policy membership.
     */
    public static List<String> onboardTenant(final String database, final String tenant, final String organisation,
                                             final String writerPassword, final String readerPassword) {
        final String flows = ident(database) + ".flows";
        final String writer = ident("writer_" + tenant);
        final String reader = ident("bi_" + tenant);
        final String policy = ident(tenant + "_iso");
        final String pinned = " SETTINGS SQL_tenant = " + literal(tenant) + " CONST, SQL_org = "
                + literal(organisation) + " CONST";
        return List.of(
                "CREATE USER IF NOT EXISTS " + writer + " IDENTIFIED WITH sha256_password BY "
                        + literal(writerPassword) + pinned,
                "ALTER USER " + writer + " IDENTIFIED WITH sha256_password BY " + literal(writerPassword),
                "GRANT flow_writer TO " + writer,
                "CREATE USER IF NOT EXISTS " + reader + " IDENTIFIED WITH sha256_password BY "
                        + literal(readerPassword) + pinned,
                "ALTER USER " + reader + " IDENTIFIED WITH sha256_password BY " + literal(readerPassword),
                "GRANT flow_reader TO " + reader,
                "CREATE ROW POLICY IF NOT EXISTS " + policy + " ON " + flows
                        + " FOR SELECT USING tenant = " + literal(tenant) + " TO " + reader);
    }

    /** Per-tenant teardown: drop the row policy and the two users; shared objects stay. */
    public static List<String> offboardTenant(final String database, final String tenant) {
        final String flows = ident(database) + ".flows";
        return List.of(
                "DROP ROW POLICY IF EXISTS " + ident(tenant + "_iso") + " ON " + flows,
                "DROP USER IF EXISTS " + ident("bi_" + tenant),
                "DROP USER IF EXISTS " + ident("writer_" + tenant));
    }

    /** Backtick-quote an identifier. The value is pre-validated to exclude backticks. */
    static String ident(final String name) {
        return "`" + name + "`";
    }

    /** Single-quote a string literal, escaping backslash then quote (ClickHouse escaping). */
    static String literal(final String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
