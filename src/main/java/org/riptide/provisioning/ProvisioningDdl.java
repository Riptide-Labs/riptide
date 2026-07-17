/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.riptide.schema.FlowsSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * The ClickHouse SQL for role-based tenant provisioning. Pure string builders — no I/O — so the
 * whole recipe is one auditable place and lifts cleanly into a future {@code riptide-admin} module.
 *
 * <p>The model puts every identical-per-tenant part into one-time objects ({@link #ensureShared})
 * — the {@code flow_writer}/{@code flow_reader} roles carrying the grants and the reader hardening,
 * and a single quota keyed by user — so {@link #onboardTenant} reduces to the scoped users, two
 * role grants, and one literal row policy. The {@code flows} schema itself is a separate, opt-in
 * recipe ({@link #bootstrapSchema}, {@code onboard --create-schema}). All statements are idempotent
 * ({@code IF NOT EXISTS} / {@code ALTER ROLE SETTINGS}), verified on ClickHouse 25.3.
 *
 * <p>Tenant/org names are validated ({@link TenantSpec}) to a safe charset; generated identifiers
 * are backtick-quoted and string literals are escaped, so neither can break out of the statement.
 */
public final class ProvisioningDdl {

    private ProvisioningDdl() {
    }

    /**
     * The opt-in schema bootstrap ({@code onboard --create-schema}): the database and {@code flows}
     * table, ordered before {@link #ensureShared} because its {@code GRANT INSERT} and
     * {@code ALTER TABLE … ADD CONSTRAINT} require the table to exist. Kept separate from
     * {@link #ensureShared} so a default run sends no CREATE statement at all — ClickHouse checks
     * the {@code CREATE DATABASE}/{@code CREATE TABLE} privilege even when {@code IF NOT EXISTS}
     * would no-op, so emitting them unconditionally would break least-privilege admins.
     */
    public static List<String> bootstrapSchema(final String database, final int ttlDays) {
        return List.of(
                FlowsSchema.createDatabase(database),
                FlowsSchema.createFlowsTable(database, ttlDays));
    }

    /** One-time shared objects: the two roles, the reader hardening, the CHECK barrier, the quota. */
    public static List<String> ensureShared(final String database, final long quotaBytes) {
        final String flows = FlowsSchema.qualifiedFlows(database);
        final List<String> statements = new ArrayList<>();
        // Additive schema upgrades first (same precondition as the GRANTs below: the table
        // exists). Emitted on every run so re-running onboard upgrades a pre-existing table in
        // place; IF NOT EXISTS makes them no-ops everywhere else.
        statements.addAll(FlowsSchema.addAdditiveColumns(database));
        statements.addAll(List.of(
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
                        + quotaBytes + " KEYED BY user_name TO flow_writer"));
        return List.copyOf(statements);
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
        final String flows = FlowsSchema.qualifiedFlows(database);
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
        final String flows = FlowsSchema.qualifiedFlows(database);
        return List.of(
                "DROP ROW POLICY IF EXISTS " + ident(tenant + "_iso") + " ON " + flows,
                "DROP USER IF EXISTS " + ident("bi_" + tenant),
                "DROP USER IF EXISTS " + ident("writer_" + tenant));
    }

    /** Validate (safe charset, via the package's single check) and backtick-quote an identifier. */
    static String ident(final String name) {
        TenantSpec.requireSafe("identifier", name);
        return "`" + name + "`";
    }

    /** Single-quote a string literal, escaping backslash then quote (ClickHouse escaping). */
    static String literal(final String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
