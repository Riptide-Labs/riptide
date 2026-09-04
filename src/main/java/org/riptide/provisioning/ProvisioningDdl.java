/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.riptide.schema.FlowsSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ClickHouse SQL for role-based tenant provisioning. Pure string builders — no I/O — so the
 * whole recipe is one auditable place and lifts cleanly into a future {@code riptide-admin} module.
 *
 * <p>The model puts every identical-per-database part into per-database objects
 * ({@link #ensureShared}) — the {@code flow_writer@<db>}/{@code flow_reader@<db>} roles carrying the
 * grants and the reader hardening, and one quota keyed by user — so {@link #onboardTenant} reduces
 * to the scoped users, two role grants, and the row policies. The {@code flows} schema itself is a
 * separate, opt-in recipe ({@link #bootstrapSchema}, {@code onboard --create-schema}), as are the
 * 1-minute rollups ({@link #bootstrapRollups}). All statements are idempotent
 * ({@code IF NOT EXISTS} / {@code OR REPLACE} / {@code ALTER ROLE SETTINGS}), verified on the
 * pinned image — ClickHouse 26.7 ({@code .github/e2e-images/clickhouse.Dockerfile}), the version
 * every claim in this file was measured against.
 *
 * <p><b>Every instance-wide object is qualified by its database</b> (#649). Users, roles and the
 * quota live in one flat, instance-wide namespace, so provisioning the same tenant id into two
 * databases on one server used to collide: the second onboarding rotated the first one's password,
 * and one shared {@code flow_writer} handed every writer {@code INSERT} on every provisioned
 * database. {@link #qualified} composes those names, and it is the only place that rule exists.
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

    /** Prefix of the per-tenant ingest writer's account name. */
    private static final String WRITER_PREFIX = "writer_";

    /** Prefix of the per-tenant BI reader's account name. */
    private static final String READER_PREFIX = "bi_";

    /**
     * The one place the qualified-name rule lives: {@code <name>@<database>}.
     *
     * <p>{@code @} is the delimiter because {@link TenantSpec} constrains every variable component
     * to {@code [A-Za-z0-9_-]+}, which cannot produce it — so the split point is unambiguous and the
     * composition is injective. {@code _} would not be: {@code (tenant=foo, database=bar_baz)} and
     * {@code (tenant=foo_bar, database=baz)} both spell {@code writer_foo_bar_baz}, so the second
     * onboarding would take over the first's account. (That exact pair is the one
     * {@code ProvisioningDdlTest.qualifyingIsInjectiveOverTheTenantDatabaseSplit} asserts, and it
     * had to be that pair: a pair differing anywhere but the split point stays distinct under
     * {@code _} too, and pins nothing.) {@code :} was rejected because it is the separator in HTTP
     * basic auth. Verified on the pinned image (26.7): a user
     * named this way is created, listed in {@code system.users} under that exact name, and
     * authenticates over both HTTP basic auth and the native protocol.
     *
     * <p>Not {@link #ident}: that validates through {@link TenantSpec#requireSafe}, which rejects
     * {@code @} — deliberately, since {@code @} must only ever be introduced here and never accepted
     * from input. Both components are validated separately instead, which is what makes that
     * guarantee real rather than a convention the callers happen to keep.
     */
    static String qualified(final String name, final String database) {
        TenantSpec.requireSafe("name", name);
        TenantSpec.requireSafe("database", database);
        return name + "@" + database;
    }

    /** As {@link #qualified}, for the objects whose name also carries a tenant. */
    private static String qualified(final String prefix, final String tenant, final String database) {
        TenantSpec.requireSafe("tenant", tenant);
        return qualified(prefix + tenant, database);
    }

    /**
     * The per-tenant ingest writer's account name. Public because the config stanza an operator
     * pastes is the only copy of this name they get, and it must not be re-derived there.
     */
    public static String writerUser(final String tenant, final String database) {
        return qualified(WRITER_PREFIX, tenant, database);
    }

    /** The per-tenant BI reader's account name. */
    public static String readerUser(final String tenant, final String database) {
        return qualified(READER_PREFIX, tenant, database);
    }

    /** The pre-#649 ingest writer's account name, carrying no database. */
    static String legacyWriterUser(final String tenant) {
        TenantSpec.requireSafe("tenant", tenant);
        return WRITER_PREFIX + tenant;
    }

    /** The pre-#649 BI reader's account name, carrying no database. */
    static String legacyReaderUser(final String tenant) {
        TenantSpec.requireSafe("tenant", tenant);
        return READER_PREFIX + tenant;
    }

    /**
     * The database-unqualified account names this rename replaced, writer first, still live on any
     * instance onboarded before #649. Kept as a named pair rather than spelled out at each site so
     * {@code offboard} (which drops them), {@code onboard} (which keeps them on the row policies
     * while they live, and warns) cannot drift apart.
     */
    static List<String> legacyUsers(final String tenant) {
        return List.of(legacyWriterUser(tenant), legacyReaderUser(tenant));
    }

    /** Base name of the write role; the database is appended by {@link #qualified} since #649. */
    private static final String WRITER_ROLE = "flow_writer";

    /** Base name of the read role. */
    private static final String READER_ROLE = "flow_reader";

    /**
     * The role carrying this database's write grants. Public for the same reason
     * {@link #writerUser} is: a caller that spelled the name out by hand would be a second place
     * remembering the rule, and the one that drifts is the one nothing runs.
     */
    public static String writerRole(final String database) {
        return qualified(WRITER_ROLE, database);
    }

    /** The role carrying this database's read grants and the reader hardening. */
    public static String readerRole(final String database) {
        return qualified(READER_ROLE, database);
    }

    /**
     * The pre-#649 role names, writer first, carrying no database.
     *
     * <p>They are instance-wide: an upgraded server still has them holding {@code INSERT}/
     * {@code SELECT} on every database provisioned before the rename, which is what
     * {@link #revokeLegacyGrants} takes back one database at a time. Kept as a named pair for the
     * reason {@link #legacyUsers} is — the probe that reads them and the statements that revoke them
     * must not drift apart.
     */
    static List<String> legacyRoles() {
        return List.of(WRITER_ROLE, READER_ROLE);
    }

    /** Backtick-quote a name this class composed. */
    private static String quote(final String name) {
        return "`" + name + "`";
    }

    /**
     * One-time per-database objects: the two roles, the reader hardening, the CHECK barrier, the
     * quota.
     *
     * <p>The roles and the quota are qualified by database (#649) — this is what makes a
     * cross-database write a privilege-level refusal ({@code ACCESS_DENIED}) rather than a predicate
     * that happens to match. A single shared {@code flow_writer} would not: {@code tenant_pinned}
     * passes whenever both databases carry the same tenant id, and the rollup policies are
     * {@code FOR SELECT} and constrain no insert at all.
     */
    public static List<String> ensureShared(final String database, final long quotaBytes) {
        final String flows = FlowsSchema.qualifiedFlows(database);
        final String writerRole = quote(writerRole(database));
        final String readerRole = quote(readerRole(database));
        final String quota = quote(qualified("flow_ingest", database));
        final List<String> statements = new ArrayList<>();
        // Additive schema upgrades first (same precondition as the GRANTs below: the table
        // exists). Emitted on every run so re-running onboard upgrades a pre-existing table in
        // place; IF NOT EXISTS makes them no-ops everywhere else.
        statements.addAll(FlowsSchema.addAdditiveColumns(database));
        statements.addAll(List.of(
                "CREATE ROLE IF NOT EXISTS " + writerRole,
                "GRANT INSERT ON " + flows + " TO " + writerRole,
                // The writer also reads flows: a materialized view runs as the inserting user, so
                // pushing a row into a rollup target requires SELECT on the view's source table.
                "GRANT SELECT ON " + flows + " TO " + writerRole,
                "CREATE ROLE IF NOT EXISTS " + readerRole,
                "GRANT SELECT ON " + flows + " TO " + readerRole,
                "GRANT SELECT ON system.databases TO " + readerRole,
                "GRANT SELECT ON system.tables TO " + readerRole,
                "GRANT SELECT ON system.columns TO " + readerRole,
                // readonly = 2 blocks writes and DDL while tolerating the read-only settings an HTTP
                // client sends per query (readonly = 1 would reject those and break the connection).
                "ALTER ROLE " + readerRole + " SETTINGS readonly = 2, allow_ddl = 0",
                "ALTER TABLE " + flows
                        + " ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant')",
                "ALTER TABLE " + flows
                        + " ADD CONSTRAINT IF NOT EXISTS org_pinned CHECK organisation = getSetting('SQL_org')",
                "CREATE QUOTA IF NOT EXISTS " + quota + " FOR INTERVAL 1 hour MAX written_bytes = "
                        + quotaBytes + " KEYED BY user_name TO " + writerRole));
        // The rollups get the same treatment as flows: the writer inserts (via the materialized
        // views), the reader selects. Driven off rollupTableNames() so a new rollup cannot be
        // added to the schema without inheriting its grants.
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String table = FlowsSchema.qualifiedRollup(database, rollup);
            statements.add("GRANT INSERT ON " + table + " TO " + writerRole);
            statements.add("GRANT SELECT ON " + table + " TO " + readerRole);
            // The writer must SEE the view's definition, to verify at startup that the rollup still
            // has the shape this version intends (#470). Without a grant ClickHouse filters the
            // view out of system.tables silently — indistinguishable from a view that does not
            // exist — so the check could neither confirm nor deny anything.
            //
            // SHOW TABLES, deliberately, and NOT SELECT. A row policy on a rollup target does not
            // apply when the same rows are read through its materialized view's name: probed on
            // 26.7, a writer holding SELECT on the _mv read every tenant's rows while the same user
            // was denied outright on the target table the policy is attached to. SELECT here would
            // hand every per-tenant writer of THIS database a cross-tenant read path around the
            // policy — the role is per-database now, but it is still shared by every tenant in it.
            // SHOW TABLES gives the visibility the check needs and no data access at all.
            statements.add("GRANT SHOW TABLES ON " + FlowsSchema.qualifiedRollupView(database, rollup)
                    + " TO " + writerRole);
        }
        return List.copyOf(statements);
    }

    /**
     * Take back the pre-#649 roles' grants on <em>one</em> database (#734) — the exact mirror of the
     * {@code GRANT}s {@link #ensureShared} emits, over the same
     * {@link FlowsSchema#rollupTableNames()} — every target and every {@code _mv} view — so a rollup
     * added to the schema is revoked here without a second edit (the #737 lesson: a hand-spelled table
     * list silently matches nothing).
     *
     * <p>Why this exists: #649 qualified every new object by its database but left the old instance-
     * wide {@code flow_writer}/{@code flow_reader} holding {@code INSERT}/{@code SELECT} on the
     * databases they already covered. A database can therefore be fully migrated and a legacy account
     * belonging to some <em>other</em>, unmigrated tenant still reaches it — measured on 26.7.
     *
     * <p><b>Per-database, and that is what makes it safe.</b> {@code REVOKE … ON db_a.flows FROM
     * flow_writer} leaves the same account still writing to an unmigrated {@code db_b} through the
     * same role (measured). The roles themselves are deliberately <em>not</em> dropped, for the same
     * reason {@link #offboardTenant} spares them: they are instance-wide, and dropping one would
     * revoke a tenant nobody offboarded.
     *
     * <p>{@code REVOKE} of a privilege the role does not hold is a silent no-op on 26.7, so emitting
     * the full mirror is correct even for a role that only ever held half of it; {@code REVOKE} from
     * a role that does not <em>exist</em> is {@code UNKNOWN_ROLE}, which is why the caller passes the
     * roles it observed rather than this emitting for both unconditionally.
     *
     * @param roles the legacy roles observed to hold a grant on this database. Filtered against
     *              {@link #legacyRoles()} so only a name this class composed reaches the statement.
     */
    public static List<String> revokeLegacyGrants(final String database, final Collection<String> roles) {
        final List<String> statements = new ArrayList<>();
        for (final String role : legacyRoles()) {
            if (!roles.contains(role)) {
                continue;
            }
            legacyGrantTables(database).forEach(table -> statements.add(revoke(table, role)));
        }
        return List.copyOf(statements);
    }

    /**
     * Every privilege {@link #ensureShared} grants on a table of this database, and therefore every
     * one the revoke must name.
     *
     * <p>{@code SHOW TABLES} is here because {@code ensureShared} grants it on each rollup's
     * materialized view, and leaving it would falsify both halves of the claim this command makes:
     * it would not be the mirror, and the database would not be closed. Not pedantry — the comment on
     * that grant records why the view is withheld from {@code SELECT} at all: a writer holding
     * {@code SELECT} on a {@code _mv} read every tenant's rows while denied on the target its policy
     * hangs on. The revoke names all three privileges on every table, which is a superset of what any
     * one table was granted; {@code REVOKE} of a privilege not held is a silent no-op (measured), and
     * a superset is the right side to err on for a hand-added grant.
     */
    static final List<String> LEGACY_PRIVILEGES = List.of("INSERT", "SELECT", "SHOW TABLES");

    /**
     * The qualified tables {@link #revokeLegacyGrants} names, in the order it names them — also what
     * the caller reports as revoked, so the report cannot claim a table the statements missed.
     *
     * <p>Each rollup's materialized view is named alongside its target, for the reason
     * {@link #LEGACY_PRIVILEGES} gives.
     */
    public static List<String> legacyGrantTables(final String database) {
        return legacyGrantTableNames().stream()
                .map(name -> FlowsSchema.qualifiedTable(database, name))
                .toList();
    }

    /**
     * The table set unqualified, and the one place it is enumerated: {@code flows}, every rollup
     * target, and every rollup view. {@link #legacyGrantTables} qualifies exactly this list, so the
     * statements and the {@code system.grants}/{@code system.row_policies} filters cannot disagree
     * about which tables are in scope — those catalogs hold the bare name in their {@code table}
     * column, so a qualified, backtick-quoted name would match nothing there and the probe would read
     * as "found nothing".
     */
    static List<String> legacyGrantTableNames() {
        final List<String> names = new ArrayList<>();
        names.add(FlowsSchema.FLOWS);
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            names.add(rollup);
            names.add(FlowsSchema.rollupViewName(rollup));
        }
        return List.copyOf(names);
    }

    private static String revoke(final String table, final String role) {
        return "REVOKE " + String.join(", ", LEGACY_PRIVILEGES) + " ON " + table + " FROM "
                + ident(role);
    }

    /**
     * Per-tenant: the scoped writer/reader users, their role grants, and one row policy per table.
     * Each user is created if absent and then has its password reconciled with {@code ALTER USER}
     * — so re-running after a secret rotation updates the credential (a plain
     * {@code CREATE … IF NOT EXISTS} would silently keep the old password). {@code ALTER USER}
     * preserves the user's {@code CONST} settings and its row-policy membership.
     *
     * <p>The writer is on the {@code flows} policy alongside the reader, and the reason is
     * <em>containment</em>, not access. A row policy is <b>not</b> deny-by-default for a user it
     * does not name: the pinned image ships
     * {@code users_without_row_policies_can_read_rows=true} (its {@code config.xml}), so a user
     * holding {@code SELECT} and named by no policy on that table reads <em>every</em> row —
     * measured on 26.7, not inferred. An unnamed writer would therefore read its neighbours' flows
     * rather than starve. Naming it constrains it to the same {@code tenant = '…'} the
     * {@code tenant_pinned} constraint already enforces on insert, so the policy grants the writer
     * no row it could not already write, and takes away every row it should never have read.
     *
     * <p>The rollup policies name the reader only. That is safe for the same reason stated
     * differently: the writer holds no {@code SELECT} on a rollup target at all (see
     * {@link #ensureShared}), so being unnamed there costs it nothing and exposes nothing. It
     * reaches a rollup by {@code INSERT} through its materialized view, which no row policy filters.
     *
     * @param liveLegacyAccounts the pre-#649 unqualified accounts for this tenant that still exist
     *                           on this server. They keep their place on the policies for as long as
     *                           they live (#649 follow-up): the policy name is unchanged by the
     *                           rename, so {@code OR REPLACE} rewrites the <em>existing</em> policy's
     *                           {@code TO} list, and dropping them from it would leave them named by
     *                           no policy — which, per the setting above, is a cross-tenant read
     *                           rather than a blank dashboard. Naming a user that does not exist
     *                           fails the statement, so only accounts observed live may be passed.
     *                           Self-healing: once the operator drops them, the next run stops
     *                           naming them.
     */
    public static List<String> onboardTenant(final String database, final String tenant, final String organisation,
                                             final String writerPassword, final String readerPassword,
                                             final Collection<String> liveLegacyAccounts) {
        final String flows = FlowsSchema.qualifiedFlows(database);
        final String writer = quote(writerUser(tenant, database));
        final String reader = quote(readerUser(tenant, database));
        // Not qualified, and deliberately so: a row policy's identity is `name ON db.table`, so
        // `acme_iso ON db_a.flows` and `acme_iso ON db_b.flows` are already two distinct objects
        // (verified — system.row_policies.name holds the full form, and both are listed). Renaming
        // would buy no isolation and cost every upgraded deployment a migration.
        final String policy = ident(tenant + "_iso");
        final List<String> flowsGrantees = flowsPolicyGrantees(tenant, database, liveLegacyAccounts);
        final List<String> rollupGrantees = rollupPolicyGrantees(tenant, database, liveLegacyAccounts);
        final String pinned = " SETTINGS SQL_tenant = " + literal(tenant) + " CONST, SQL_org = "
                + literal(organisation) + " CONST";
        final List<String> statements = new ArrayList<>(List.of(
                "CREATE USER IF NOT EXISTS " + writer + " IDENTIFIED WITH sha256_password BY "
                        + literal(writerPassword) + pinned,
                "ALTER USER " + writer + " IDENTIFIED WITH sha256_password BY " + literal(writerPassword),
                "GRANT " + quote(writerRole(database)) + " TO " + writer,
                "CREATE USER IF NOT EXISTS " + reader + " IDENTIFIED WITH sha256_password BY "
                        + literal(readerPassword) + pinned,
                "ALTER USER " + reader + " IDENTIFIED WITH sha256_password BY " + literal(readerPassword),
                "GRANT " + quote(readerRole(database)) + " TO " + reader,
                rowPolicy(policy, flows, tenant, String.join(", ", flowsGrantees))));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            statements.add(rowPolicy(policy, FlowsSchema.qualifiedRollup(database, rollup), tenant,
                    String.join(", ", rollupGrantees)));
        }
        return List.copyOf(statements);
    }

    /**
     * Who this tenant's {@code flows} policy names: the qualified pair, plus any pre-#649 account
     * still live. Mirrors the qualified accounts exactly — the legacy writer joins {@code flows}
     * because the legacy writer, like the new one, holds {@code SELECT} there.
     */
    private static List<String> flowsPolicyGrantees(final String tenant, final String database,
            final Collection<String> liveLegacyAccounts) {
        final List<String> grantees = new ArrayList<>(List.of(
                quote(readerUser(tenant, database)), quote(writerUser(tenant, database))));
        addIfLive(grantees, legacyReaderUser(tenant), liveLegacyAccounts);
        addIfLive(grantees, legacyWriterUser(tenant), liveLegacyAccounts);
        return grantees;
    }

    /**
     * Who a rollup policy names: the readers only, for the reason in {@link #onboardTenant} — no
     * writer, new or legacy, holds {@code SELECT} on a rollup target.
     */
    private static List<String> rollupPolicyGrantees(final String tenant, final String database,
            final Collection<String> liveLegacyAccounts) {
        final List<String> grantees = new ArrayList<>(List.of(quote(readerUser(tenant, database))));
        addIfLive(grantees, legacyReaderUser(tenant), liveLegacyAccounts);
        return grantees;
    }

    private static void addIfLive(final List<String> grantees, final String legacy,
            final Collection<String> liveLegacyAccounts) {
        if (liveLegacyAccounts.contains(legacy)) {
            grantees.add(ident(legacy));
        }
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
     * users; the per-database roles, constraints and quota stay. A policy left behind on a rollup
     * would keep denying rows there after the tenant is gone.
     *
     * <p>Both namings are dropped. An instance onboarded before #649 holds this tenant's credential
     * under the unqualified {@code writer_<t>}/{@code bi_<t>}, and dropping only the qualified names
     * there would report a revocation that did not happen — the operator's whole reason for running
     * {@code offboard}. {@code IF EXISTS} makes each pair a no-op on the instance that lacks it.
     *
     * <p><b>The legacy drop is instance-wide, and this method cannot make it otherwise.</b> The
     * legacy names are keyed on the tenant alone, so offboarding {@code acme} from {@code db_b}
     * drops the same {@code writer_acme} that an unmigrated {@code db_a} is still ingesting with.
     * That is inherent to the naming this change replaced — the credential genuinely is one object
     * — and dropping it is still right, because leaving it would be the false revocation above. The
     * caller must say so: {@code ProvisioningCommand.offboard} names the consequence, and
     * {@code multi-tenancy.md} tells the operator to re-onboard any other database of this tenant
     * that was still on the old naming.
     *
     * <p>The legacy {@code flow_writer}/{@code flow_reader} roles are deliberately <em>not</em>
     * dropped: they are instance-wide and may still be granted to another tenant's legacy user.
     */
    public static List<String> offboardTenant(final String database, final String tenant) {
        final String policy = ident(tenant + "_iso");
        final List<String> statements = new ArrayList<>();
        statements.add("DROP ROW POLICY IF EXISTS " + policy + " ON " + FlowsSchema.qualifiedFlows(database));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            statements.add("DROP ROW POLICY IF EXISTS " + policy + " ON "
                    + FlowsSchema.qualifiedRollup(database, rollup));
        }
        statements.add("DROP USER IF EXISTS " + quote(readerUser(tenant, database)));
        statements.add("DROP USER IF EXISTS " + quote(writerUser(tenant, database)));
        for (final String legacy : legacyUsers(tenant)) {
            statements.add("DROP USER IF EXISTS " + ident(legacy));
        }
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
