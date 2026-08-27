/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.riptide.schema.FlowsSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ClickHouse SQL for role-based tenant provisioning. Pure string builders — no I/O — so the
 * whole recipe is one auditable place and lifts cleanly into a future {@code riptide-admin} module.
 *
 * <p>The model puts every identical-per-tenant part into one-time objects ({@link #ensureShared})
 * — the {@code flow_writer}/{@code flow_reader} roles carrying the grants and the reader hardening,
 * and a single quota keyed by user — so {@link #onboardTenant} reduces to the scoped users, two
 * role grants, and the row policies. The {@code flows} schema itself is a separate, opt-in recipe
 * ({@link #bootstrapSchema}, {@code onboard --create-schema}), as are the 1-minute rollups
 * ({@link #bootstrapRollups}). All statements are idempotent ({@code IF NOT EXISTS} /
 * {@code OR REPLACE} / {@code ALTER ROLE SETTINGS}), verified on ClickHouse 25.3.
 *
 * <p>The rollups are provisioned as first-class tables: every grant and every row policy that
 * covers {@code flows} covers each rollup target too. Both are driven off
 * {@link FlowsSchema#rollupTableNames()}, so a rollup added to the schema is picked up here without
 * a second edit — the failure mode being guarded against is a new rollup silently missing its
 * tenant isolation.
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

    /**
     * The opt-in rollup bootstrap: the 1-minute <em>target tables</em> only. The views are
     * {@link #bootstrapRollupViews}, emitted after {@link #repairRollups} — see there for why.
     *
     * <p>The additive columns come first because the rollups select {@code srcCountry},
     * {@code dstCountry} and {@code exporterName} — on a pre-0.5 table those columns do not exist
     * yet, and a materialized view referencing a missing column fails to create.</p>
     *
     * <p><b>A caller that emits this must also emit {@link #bootstrapRollupViews}</b>, under the
     * same condition. Targets without views are four tables nothing writes to, which reports as a
     * healthy-looking empty rollup rather than as an error.</p>
     */
    public static List<String> bootstrapRollups(final String database) {
        final List<String> statements = new ArrayList<>();
        statements.addAll(FlowsSchema.addAdditiveColumns(database));
        statements.addAll(FlowsSchema.createRollupTables(database));
        return List.copyOf(statements);
    }

    /**
     * The rollup materialized views, emitted <em>after</em> {@link #repairRollups}.
     *
     * <p>Split from {@link #bootstrapRollups} because ordering is correctness here, not tidiness.
     * {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} validates its SELECT against the target even
     * when the view exists and the statement no-ops, and that SELECT names every dimension this
     * version intends. Emitted before the repair, it fails against a target that has not been
     * brought up to date — aborting the whole {@code onboard} run before the repair statements that
     * would have fixed it, which is the one path a provisioned deployment has.</p>
     *
     * @param skip rollups whose target this run is NOT repairing — a refused one still lacks the
     *             column, and its SELECT names it, so the CREATE fails with
     *             {@code THERE_IS_NO_COLUMN} and aborts the whole run. That leaves the tenant
     *             unprovisioned over a rollup that was deliberately left alone: no roles, no users,
     *             no password rotation. A rollup with no view is a rollup that stays empty and is
     *             declined, which is the outcome refusing it already implied.
     */
    public static List<String> bootstrapRollupViews(final String database, final Set<String> skip) {
        final var views = FlowsSchema.createRollupViewsByRollup(database);
        return views.entrySet().stream()
                .filter(view -> !skip.contains(view.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * In-place repair for rollups that already exist (#470), for the rollups a caller has decided
     * are safe to repair.
     *
     * <p>{@code CREATE … IF NOT EXISTS} no-ops over an existing rollup, so without this a
     * provisioned deployment stays on its original shape forever — its collector runs in validate
     * mode and never issues DDL, making {@code onboard} the only path it has.</p>
     *
     * <p>The caller supplies the verdict from {@link FlowsSchema#planRollupRepair} rather than this
     * emitting for every rollup unconditionally. <b>A sorting-key shrink is not rejected by the
     * server</b> on the deployments that matter: #571 froze the primary key, so on an upgraded
     * table the prefix rule no longer covers the removed column and {@code MODIFY ORDER BY} to a
     * shorter key succeeds — verified on 26.7. An unguarded emission here would change a rollup's
     * grain in place with no error and no re-aggregation, which is exactly what the collector's
     * path refuses.</p>
     *
     * <p>Targets before views, because a view's SELECT names the columns the target {@code ALTER}
     * adds. Note this is <em>not</em> enforced by the server on this path: {@code MODIFY QUERY} does
     * not validate against its target, and silently drops an unmatched column on every insert
     * instead of failing — see {@link FlowsSchema#modifyRollupViews}. Order is correctness here, and
     * getting it wrong produces no error at all.</p>
     *
     * @param rollups        the targets to {@code ALTER}, from the caller's repair plan
     * @param viewsToRepoint the rollups whose view should be re-pointed at this version's SELECT.
     *                       Independent of {@code rollups}: a run that altered a target and then
     *                       failed before its {@code MODIFY QUERY} leaves a target the next run
     *                       finds current, so a view-repair list derived from target shapes would
     *                       never fix it. Excludes any rollup whose view does not exist (the
     *                       statement would fail with {@code UNKNOWN_TABLE} and abort the run) and
     *                       any the planner refused (its target is not being repaired).
     */
    public static List<String> repairRollups(final String database, final List<String> rollups,
            final Set<String> viewsToRepoint) {
        final List<String> statements = new ArrayList<>();
        final var alters = FlowsSchema.alterRollupTargets(database);
        final var modifies = FlowsSchema.modifyRollupViews(database);
        rollups.forEach(rollup -> statements.add(alters.get(rollup)));
        viewsToRepoint.forEach(rollup -> statements.add(modifies.get(rollup)));
        return List.copyOf(statements);
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
                // The writer also reads flows: a materialized view runs as the inserting user, so
                // pushing a row into a rollup target requires SELECT on the view's source table.
                "GRANT SELECT ON " + flows + " TO flow_writer",
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
        // The rollups get the same treatment as flows: the writer inserts (via the materialized
        // views), the reader selects. Driven off rollupTableNames() so a new rollup cannot be
        // added to the schema without inheriting its grants.
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String table = FlowsSchema.qualifiedRollup(database, rollup);
            statements.add("GRANT INSERT ON " + table + " TO flow_writer");
            statements.add("GRANT SELECT ON " + table + " TO flow_reader");
            // The writer must SEE the view's definition, to verify at startup that the rollup still
            // has the shape this version intends (#470). Without a grant ClickHouse filters the
            // view out of system.tables silently — indistinguishable from a view that does not
            // exist — so the check could neither confirm nor deny anything.
            //
            // SHOW TABLES, deliberately, and NOT SELECT. A row policy on a rollup target does not
            // apply when the same rows are read through its materialized view's name: probed on
            // 26.7, a writer holding SELECT on the _mv read every tenant's rows while the same user
            // was denied outright on the target table the policy is attached to. SELECT here would
            // hand every per-tenant writer a cross-tenant read path around the policy. SHOW TABLES
            // gives the visibility the check needs and no data access at all.
            statements.add("GRANT SHOW TABLES ON " + FlowsSchema.qualifiedRollupView(database, rollup)
                    + " TO flow_writer");
        }
        return List.copyOf(statements);
    }

    /**
     * Per-tenant: the scoped writer/reader users, their role grants, and one row policy per table.
     * Each user is created if absent and then has its password reconciled with {@code ALTER USER}
     * — so re-running after a secret rotation updates the credential (a plain
     * {@code CREATE … IF NOT EXISTS} would silently keep the old password). {@code ALTER USER}
     * preserves the user's {@code CONST} settings and its row-policy membership.
     *
     * <p>The writer is on the {@code flows} policy alongside the reader. A row policy on a table is
     * deny-by-default for anyone it does not name, and the writer must read {@code flows} for the
     * rollup views to push — omitting it would leave the materialized views silently pushing
     * nothing. Its predicate is the same {@code tenant = '…'} the {@code tenant_pinned} constraint
     * enforces on insert, so the policy grants the writer no row it could not already write.
     *
     * <p>The rollup policies name the reader only: the writer reaches a rollup by {@code INSERT}
     * through its materialized view, which no row policy filters.
     *
     * <p>Users and policies are namespaced by database: {@code writer_<database>_<tenant>},
     * {@code bi_<database>_<tenant>}, and {@code <database>_<tenant>_iso}. ClickHouse users and
     * roles are instance-wide, so omitting the database would share credentials and policies across
     * every database provisioned with the same tenant identifier — the second onboarding would
     * change the shared users' passwords, the shared roles would accumulate grants for both
     * databases, and offboarding one database would drop users still needed by the other.
     */
    public static List<String> onboardTenant(final String database, final String tenant, final String organisation,
                                             final String writerPassword, final String readerPassword) {
        final String flows = FlowsSchema.qualifiedFlows(database);
        final String writer = ident("writer_" + database + "_" + tenant);
        final String reader = ident("bi_" + database + "_" + tenant);
        final String policy = ident(database + "_" + tenant + "_iso");
        final String pinned = " SETTINGS SQL_tenant = " + literal(tenant) + " CONST, SQL_org = "
                + literal(organisation) + " CONST";
        final List<String> statements = new ArrayList<>(List.of(
                "CREATE USER IF NOT EXISTS " + writer + " IDENTIFIED WITH sha256_password BY "
                        + literal(writerPassword) + pinned,
                "ALTER USER " + writer + " IDENTIFIED WITH sha256_password BY " + literal(writerPassword),
                "GRANT flow_writer TO " + writer,
                "CREATE USER IF NOT EXISTS " + reader + " IDENTIFIED WITH sha256_password BY "
                        + literal(readerPassword) + pinned,
                "ALTER USER " + reader + " IDENTIFIED WITH sha256_password BY " + literal(readerPassword),
                "GRANT flow_reader TO " + reader,
                rowPolicy(policy, flows, tenant, reader + ", " + writer)));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            statements.add(rowPolicy(policy, FlowsSchema.qualifiedRollup(database, rollup), tenant, reader));
        }
        return List.copyOf(statements);
    }

    /**
     * One tenant-isolating row policy. {@code OR REPLACE} rather than {@code IF NOT EXISTS} for the
     * same reason {@link #onboardTenant} re-issues {@code ALTER USER}: a policy left over from an
     * earlier run keeps its old {@code TO} list, so a re-run would not pick up a changed grantee.
     */
    private static String rowPolicy(final String policy, final String table, final String tenant, final String to) {
        return "CREATE ROW POLICY OR REPLACE " + policy + " ON " + table
                + " FOR SELECT USING tenant = " + literal(tenant) + " TO " + to;
    }

    /**
     * Per-tenant teardown: drop the policy from {@code flows} and from every rollup, then the two
     * users; shared objects stay. A policy left behind on a rollup would keep denying rows there
     * after the tenant is gone. Users and policies are namespaced by database to prevent lifecycle
     * collisions when the same tenant identifier is provisioned across multiple databases.
     */
    public static List<String> offboardTenant(final String database, final String tenant) {
        final String policy = ident(database + "_" + tenant + "_iso");
        final List<String> statements = new ArrayList<>();
        statements.add("DROP ROW POLICY IF EXISTS " + policy + " ON " + FlowsSchema.qualifiedFlows(database));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            statements.add("DROP ROW POLICY IF EXISTS " + policy + " ON "
                    + FlowsSchema.qualifiedRollup(database, rollup));
        }
        statements.add("DROP USER IF EXISTS " + ident("bi_" + database + "_" + tenant));
        statements.add("DROP USER IF EXISTS " + ident("writer_" + database + "_" + tenant));
        return List.copyOf(statements);
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
