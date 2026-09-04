/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import com.clickhouse.client.api.Client;
import lombok.extern.slf4j.Slf4j;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Provisions and de-provisions a tenant against ClickHouse using an admin connection. This is the
 * whole admin-side of multi-tenancy: it ensures the per-database role/constraint/quota objects
 * (qualified {@code @<database>} since #649 — see {@link ProvisioningDdl}), then creates (or drops)
 * the per-tenant scoped users and row policy. It depends only on a ClickHouse {@link Client} and
 * {@link SecretResolvers}, so it carries no Spring coupling and lifts cleanly into a future
 * {@code riptide-admin} module.
 *
 * <p>The caller owns the admin {@link Client} (built from admin credentials supplied explicitly at
 * invocation — never the collector's scoped credential) and its lifecycle.
 */
@Slf4j
public final class TenantProvisioner {

    private final Client admin;
    private final SecretResolvers secretResolvers;

    public TenantProvisioner(final Client admin, final SecretResolvers secretResolvers) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.secretResolvers = Objects.requireNonNull(secretResolvers, "secretResolvers");
    }

    /**
     * Ensure the shared objects and this tenant's users/policy exist (idempotent). Returns the
     * riptide configuration stanza for the tenant's collector, referencing the same writer secret.
     *
     * <p>A pre-flight check verifies the database, the {@code flows} table and the rollups exist
     * <em>before any statement runs</em>. If they are missing, the run fails unless
     * {@code createSchema} is set —
     * a typo'd database name must fail loudly, not silently provision a phantom database with the
     * per-database roles granted on it. The bootstrap statements are only <em>emitted</em> when
     * actually creating: ClickHouse checks the {@code CREATE} privileges even when
     * {@code IF NOT EXISTS} would no-op, so a re-run (e.g. a password rotation) by an admin holding
     * no {@code CREATE} keeps working.
     *
     * <p>A second pre-flight, also before any statement runs, asks which pre-#649 accounts this
     * tenant still has ({@link #readLiveLegacyAccounts}) — they must stay named on the row policies
     * this run re-issues. That read needs {@code SHOW USERS}, and the run is refused outright if it
     * cannot be answered; see that method for why proceeding is worse than failing.
     */
    public OnboardResult onboard(final TenantSpec spec, final boolean createSchema, final int ttlDays) {
        final String writerPassword = resolve(spec.writerSecret());
        final String readerPassword = resolve(spec.readerSecret());

        final var statements = new ArrayList<String>();
        final boolean bootstrap = !flowsTableExists(spec.database());
        if (bootstrap) {
            if (!createSchema) {
                throw new ProvisioningException(
                        "database '" + spec.database() + "' has no flows table — re-run with"
                                + " --create-schema to bootstrap it, or check the --database value"
                                + " for typos (an admin-provisioned table is also accepted)", null);
            }
            statements.addAll(ProvisioningDdl.bootstrapSchema(spec.database(), ttlDays));
        }
        // Same opt-in gate for the rollups, checked separately: a database provisioned before the
        // rollups existed has a perfectly good flows table and would otherwise pass the check above
        // while silently lacking them. Short-circuited on bootstrap — a database we are creating
        // right now cannot have rollups, so there is no point asking ClickHouse eight times.
        // The plan is computed before the gate below, not after, because a REFUSED rollup must not
        // count as missing. Its view is deliberately never created, so rollupsExist() would stay
        // false forever and every later run — a password rotation, a re-issued config stanza —
        // would fail demanding --create-schema for a state that flag cannot fix. Refusing a rollup
        // must cost that rollup, not the tenant.
        final Optional<FlowsSchema.RepairPlan> plan = plannedRollupRepair(spec.database());
        final Set<String> refused = plan.map(p -> p.refused().keySet()).orElseGet(Set::of);
        final boolean rollupsMissing = bootstrap || !rollupsExist(spec.database(), refused);
        if (rollupsMissing) {
            if (!createSchema) {
                throw new ProvisioningException(
                        "database '" + spec.database() + "' is missing the 1-minute rollup tables or"
                                + " their materialized views — re-run with --create-schema to add"
                                + " them. This creates tables and materialized views only; the flows"
                                + " table and its data are untouched, and the rollups cover traffic"
                                + " from creation onward (a materialized view does not backfill)", null);
            }
            statements.addAll(ProvisioningDdl.bootstrapRollups(spec.database()));
        }
        // Rollups that already exist are repaired in place, since CREATE ... IF NOT EXISTS no-ops
        // over them (#470). Planned against the live sorting keys, using the same rule the
        // collector applies, so a change that would shrink a key is refused here too — the server
        // does not reject it once #571 froze the primary key.
        //
        // Targets are repaired BEFORE the views are created, and the split exists for that reason:
        // a view's SELECT names every dimension this version intends, and CREATE ... IF NOT EXISTS
        // validates it against the target even when it no-ops. Creating views first would abort the
        // run against a stale target — before the repair that would have fixed it.
        // Planned from the views' OWN stored SELECT, through the same shared function the collector
        // uses — so the two paths cannot disagree, which until now they did. Re-pointing every
        // present view unconditionally emitted four ALTERs against an already-correct database (the
        // spec requires a re-run to issue none) and carried no downgrade guard, so a view from a
        // newer version would have been silently narrowed.
        //
        // Still wider than the target plan: a run that repaired a target and then failed before its
        // MODIFY QUERY leaves the next run a target it finds current, so a view list derived from
        // target shapes would never fix it.
        final Set<String> repointable = bootstrap || plan.isEmpty()
                ? Set.of()
                : viewRepair(spec.database(), refused);
        statements.addAll(ProvisioningDdl.repairRollups(
                spec.database(), plan.map(FlowsSchema.RepairPlan::repair).orElseGet(List::of), repointable));
        // Under the SAME condition as the targets above, not just on bootstrap. rollupsExist() is
        // false when any target OR any view is missing — its whole reason for checking both halves
        // is the interrupted bootstrap that leaves a target without its view. Gating the views on
        // `bootstrap` alone would create four target tables nothing ever feeds, and re-running would
        // repeat the same no-op forever, because the targets it just created do not make
        // rollupsExist() true on their own. CREATE ... IF NOT EXISTS no-ops over the views that are
        // already there, so emitting all four is correct whenever any one of them is absent.
        if (rollupsMissing) {
            // Skip-all when the plan could not be computed, the same way `repointable` above
            // degrades. An unread catalog means no target was altered, so every CREATE would be
            // validated against a possibly stale target — and CREATE ... IF NOT EXISTS DOES validate
            // even when it no-ops, so one stale target aborts the run before ensureShared and
            // onboardTenant. Degrading to "skip nothing" here would leave the tenant unprovisioned
            // for the same reason a refused rollup once did.
            statements.addAll(ProvisioningDdl.bootstrapRollupViews(
                    spec.database(), plan.isPresent() ? refused : Set.copyOf(FlowsSchema.rollupTableNames())));
        }
        statements.addAll(ProvisioningDdl.ensureShared(spec.database(), spec.quotaBytes()));
        // Probed BEFORE the statements are built, not after they run, and that ordering is the
        // whole fix: the row policies this run replaces must keep naming any pre-#649 account that
        // is still live, or the upgrade turns them into cross-tenant readers. It throws rather than
        // degrading, and this is the point at which that is still free — nothing has executed.
        final List<String> legacyAccounts = readLiveLegacyAccounts(spec.tenant());
        statements.addAll(ProvisioningDdl.onboardTenant(
                spec.database(), spec.tenant(), spec.organisation(), writerPassword, readerPassword,
                legacyAccounts));
        execute(statements);

        return new OnboardResult(configStanza(spec), bootstrap, legacyAccounts);
    }

    /**
     * The database-unqualified accounts for this tenant that are still live (#649).
     *
     * <p>Load-bearing twice over. They are kept on the row policies for as long as they live — see
     * {@link ProvisioningDdl#onboardTenant} — and they are reported to the operator, who is the only
     * one who may drop them: a rolling upgrade still has the tenant's collector authenticating as
     * the legacy writer until the new stanza is pasted, so removing it here would take ingest down.
     *
     * <p><b>Failure throws.</b> ClickHouse <em>refuses</em> this query rather than filtering it: an
     * admin holding only the privileges {@code multi-tenancy.md} lists gets
     * {@code Code: 497 … it's necessary to have the grant SELECT ON system.users (ACCESS_DENIED)},
     * measured on the pinned image. Degrading to "found nothing" there is indistinguishable from a
     * clean instance, and the consequence is not a missing warning: this run would rewrite
     * {@code <tenant>_iso} naming only the qualified pair, leaving a still-live pre-rename account
     * named by no policy — which, on a server with the shipped
     * {@code users_without_row_policies_can_read_rows} default, reads every tenant's rows. So the
     * whole run is refused instead, which is safe precisely because this runs before
     * {@code execute}: nothing has been changed when it throws.
     *
     * <p>Reading the existing policy instead would not avoid the grant — {@code system.row_policies}
     * and {@code SHOW CREATE ROW POLICY} are refused to the same admin, also measured.
     *
     * @throws ProvisioningException if the catalog cannot be read; the message names the exact grant
     */
    private List<String> readLiveLegacyAccounts(final String tenant) {
        final List<String> legacy = ProvisioningDdl.legacyUsers(tenant);
        final Set<String> live = new LinkedHashSet<>();
        final String in = legacy.stream().map(ProvisioningDdl::literal).collect(Collectors.joining(", "));
        try (var users = this.admin.queryRecords(
                "SELECT name AS n FROM system.users WHERE name IN (" + in + ")").get()) {
            users.forEach(record -> live.add(record.getString("n")));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException(legacyProbeFailure(tenant, "interrupted"), e);
        } catch (final Exception e) {
            throw new ProvisioningException(legacyProbeFailure(tenant, describe(e)), e);
        }
        // Filtered rather than returned in the server's order, so the warning names the writer
        // first every time.
        return legacy.stream().filter(live::contains).toList();
    }

    /** The one wording for a refused legacy probe, so the abort and the offboard note agree. */
    private static String legacyProbeFailure(final String tenant, final String cause) {
        return "could not check whether tenant '" + tenant + "' still has pre-rename"
                + " (database-unqualified) accounts on this server: " + cause
                + ". Refusing to continue — nothing has been changed. This run would re-issue the"
                + " row policy '" + tenant + "_iso', and an account dropped from its TO list while"
                + " it still holds SELECT is left governed by no policy at all, which on a server"
                + " with the shipped users_without_row_policies_can_read_rows default reads EVERY"
                + " tenant's rows. Grant the admin the privilege that answers the question and"
                + " re-run: GRANT SHOW USERS ON *.* TO <your --admin-user>";
    }

    /** {@code getMessage()} is null for several client exceptions, and null formats badly. */
    private static String describe(final Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * Take back the pre-#649 roles' grants on one database (#734), refusing unless that database has
     * no legacy account left serving it.
     *
     * <p>#649 qualified every new object by its database but left the instance-wide
     * {@code flow_writer}/{@code flow_reader} holding {@code INSERT}/{@code SELECT} on the databases
     * they already covered — so a fully migrated database is still reachable by a legacy account
     * belonging to some <em>other</em>, unmigrated tenant. This closes that, one database at a time,
     * which is what keeps it safe: the revoke names only this database's tables, and the same account
     * keeps working against an unmigrated one through the same role.
     *
     * <p><b>Both catalog reads fail closed</b>, and that is the whole safety argument rather than a
     * nicety. ClickHouse <em>refuses</em> {@code system.grants} and {@code system.row_policies} to an
     * admin without the privilege instead of filtering the rows away (measured on the pinned 26.7:
     * {@code Code: 497 … SELECT for at least one column on system.row_policies}), so a probe that
     * degraded to "found nothing" would be indistinguishable from a clean answer. On the first read
     * that reports the exposure as already closed; on the second it revokes a credential a live
     * collector is still ingesting with. Both refuse instead, and both refuse before any statement
     * runs.
     *
     * <p><b>So does every way of finding nothing.</b> Failing closed on a refused read is not enough
     * if a successful read can be empty for two different reasons, and here three can:
     * <ul>
     *   <li>a database that does not exist matches no grant and no policy — a typo'd
     *       {@code --database} would otherwise exit 0 with an all-clear;</li>
     *   <li>a database with <em>no</em> row policies names no pre-rename grantee, and so does a
     *       hand-provisioned one whose collector is still on the old credential;</li>
     *   <li>a policy created {@code TO ALL} carries an empty {@code apply_to_list}, so the grantee
     *       names say nobody is served on a database where everyone is.</li>
     * </ul>
     * Each is refused with what it could not tell rather than read as a clean answer.
     *
     * @param dryRun report the statements without running them — the only way an operator sees the
     *               SQL, since the reports name tables rather than statements
     * @return what was (or would be) revoked; empty roles mean there was nothing to revoke — never
     *         "could not tell", because every read that cannot tell throws
     * @throws ProvisioningException if the database or its {@code flows} table is absent, if a
     *         pre-rename grantee still serves this database, if a legacy role holds a grant wider
     *         than this can revoke, or if either catalog read is refused
     */
    public RevokeLegacyResult revokeLegacyGrants(final String database, final boolean dryRun) {
        TenantSpec.requireSafe("database", database);
        // First, and for the reason onboard's own pre-flight exists (#267): a typo'd --database
        // otherwise matches no grant and no policy, and every later check reads that as "clean".
        // For a command whose only job is closing an exposure, exiting 0 with an all-clear over a
        // database that does not exist is the worst failure available.
        if (!flowsTableExists(database)) {
            throw new ProvisioningException("database '" + database + "' has no flows table, so there"
                    + " is nothing here to revoke and no way to tell a migrated database from a"
                    + " mistyped one. Nothing has been changed. Check the --database value for typos"
                    + " (it defaults to 'riptide')", null);
        }
        final LegacyGrants grants = legacyGrantsOn(database);
        if (!grants.wider().isEmpty()) {
            throw new ProvisioningException(widerGrantFailure(database, grants.wider()), null);
        }
        if (grants.roles().isEmpty()) {
            // Deliberately before the row-policy read: with nothing to revoke there is no decision to
            // make safe, and demanding a second catalog privilege to say "nothing to do" would fail
            // the one run that is provably harmless.
            return new RevokeLegacyResult(List.of(), List.of(), List.of());
        }
        final PolicyCheck served = legacyGranteesServing(database);
        if (served.policies() == 0) {
            throw new ProvisioningException(noPolicyFailure(database), null);
        }
        if (served.unenumerable()) {
            throw new ProvisioningException(applyToAllFailure(database), null);
        }
        if (!served.unqualified().isEmpty()) {
            throw new ProvisioningException(stillServedFailure(database, served.unqualified()), null);
        }
        final List<String> statements = ProvisioningDdl.revokeLegacyGrants(database, grants.roles());
        if (!dryRun) {
            try {
                execute(statements);
            } catch (final ProvisioningException e) {
                // Every other refusal here promises "nothing has been changed"; this one cannot, and
                // saying so is the difference between an operator re-running and an operator
                // believing the database is untouched. The likeliest cause is the one privilege
                // neither probe can detect: revoking a role's privilege needs GRANT OPTION on the
                // database, and both reads pass without it.
                throw new ProvisioningException("the revoke failed part-way through on database '"
                        + database + "', which may leave it HALF-REVOKED — earlier statements ran."
                        + " Re-run this command: the statements are idempotent, so a re-run finishes"
                        + " the job. If it fails again, check that the admin holds GRANT OPTION"
                        + " (GRANT INSERT, SELECT ON " + database + ".* TO <your --admin-user> WITH"
                        + " GRANT OPTION) — that is the one privilege the catalog reads cannot check."
                        + " Cause: " + describe(e), e);
            }
        }
        return new RevokeLegacyResult(grants.roles(), ProvisioningDdl.legacyGrantTables(database),
                statements);
    }

    /**
     * What the pre-#649 roles hold that reaches this database, split by whether this command can
     * take it back.
     *
     * @param roles the legacy roles holding a per-table grant on one of this database's tables,
     *              writer first — exactly what a {@code REVOKE} may name. A role that does not exist
     *              never appears, which matters: naming one is {@code UNKNOWN_ROLE}.
     * @param wider grants broader than those tables ({@code ON <db>.*}, {@code ON *.*}), as
     *              {@code role -> scope}. They are found and reported rather than revoked; see
     *              {@link #widerGrantFailure}.
     */
    private record LegacyGrants(List<String> roles, Map<String, String> wider) {
    }

    /**
     * Read {@code system.grants} for what the pre-#649 roles hold on this database.
     *
     * <p>{@code is_partial_revoke = 0} because a row in {@code system.grants} is not always a grant:
     * revoking per-table against a database-wide grant records the exception as a row of its own
     * (measured on 26.7), and counting those would make every re-run claim there is something left to
     * take back.
     *
     * <p>Grants wider than this command's tables are matched deliberately — {@code database IS NULL}
     * is {@code ON *.*}, {@code table IS NULL} is {@code ON <db>.*}. Narrowing the probe to per-table
     * grants would let a role holding {@code SELECT ON <db>.*} read as "nothing to revoke", trading a
     * false claim for a missed exposure. They are separated rather than revoked because a per-table
     * {@code REVOKE} only carves those five tables out of such a grant: the role keeps everything else
     * in the database, the success line would be false, and the wider row survives with
     * {@code is_partial_revoke = 0} so no re-run would ever be a no-op.
     *
     * @throws ProvisioningException if the catalog cannot be read; the message names the exact grant
     */
    private LegacyGrants legacyGrantsOn(final String database) {
        final Set<String> exact = new LinkedHashSet<>();
        final Map<String, String> wider = new LinkedHashMap<>();
        final String roles = ProvisioningDdl.legacyRoles().stream()
                .map(ProvisioningDdl::literal).collect(Collectors.joining(", "));
        try (var grants = this.admin.queryRecords(
                "SELECT DISTINCT role_name AS r,"
                        + " (database IS NULL OR table IS NULL) AS wide,"
                        + " if(database IS NULL, '*.*', concat(database, '.*')) AS scope"
                        + " FROM system.grants"
                        + " WHERE role_name IN (" + roles + ")"
                        + " AND is_partial_revoke = 0"
                        + " AND access_type IN (" + accessTypes() + ")"
                        + " AND (database IS NULL OR (database = " + ProvisioningDdl.literal(database)
                        + " AND (table IS NULL OR table IN (" + tableNames() + "))))"
                        + " ORDER BY r").get()) {
            grants.forEach(record -> {
                if (record.getLong("wide") == 1) {
                    wider.put(record.getString("r"), record.getString("scope"));
                } else {
                    exact.add(record.getString("r"));
                }
            });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException(grantProbeFailure(database, "interrupted"), e);
        } catch (final Exception e) {
            throw new ProvisioningException(grantProbeFailure(database, describe(e)), e);
        }
        // Filtered through our own list rather than returned in the server's order, so no string read
        // from the catalog reaches a REVOKE and the writer is always named first.
        return new LegacyGrants(
                ProvisioningDdl.legacyRoles().stream().filter(exact::contains).toList(),
                Map.copyOf(wider));
    }

    /**
     * What this database's row policies say about who still depends on it.
     *
     * @param policies     how many policies exist on this database's flows and rollup tables.
     *                     <b>Zero is not an answer</b>: it is equally what a fully migrated database
     *                     and a never-onboarded, hand-provisioned or policy-dropped one look like, and
     *                     the second still has a live collector on the legacy role.
     * @param unenumerable a policy applies to <em>all</em> principals ({@code apply_to_all}, or an
     *                     {@code EXCEPT} list), so who is served cannot be enumerated from the names.
     *                     Measured: {@code TO ALL} leaves {@code apply_to_list} empty, which the name
     *                     check alone reads as "nobody is served" on a database where everyone is.
     * @param unqualified  the grantees carrying no {@code @<database>}, sorted
     */
    private record PolicyCheck(int policies, boolean unenumerable, List<String> unqualified) {
    }

    /**
     * Read {@code system.row_policies} for who still depends on this database.
     *
     * <p>This is the question that can actually be answered per database. "Does any legacy user exist
     * on this server" cannot: the legacy names carry no database, so a legacy account of a tenant
     * living entirely in another database would block a revoke that has nothing to do with it — and
     * that account is precisely the exposure this command closes. {@code onboard} keeps every live
     * legacy account named on the policies of the database it is run against, and stops naming one
     * the operator has retired, so an unqualified name here means that grantee is still depended on
     * <em>here</em>.
     *
     * <p>Any name without {@code @} counts, not just {@code writer_*}/{@code bi_*}: the qualified
     * naming is the only thing that makes a principal this-database-only, so anything else is either a
     * pre-rename account or a hand-added grantee, and both are reasons to stop and let a human look.
     * They are called grantees rather than accounts because ClickHouse puts <em>roles</em> in
     * {@code apply_to_list} too (measured), so the advice must not assume a {@code DROP USER}.
     *
     * <p>The empty string appended to each policy's list keeps one row per policy even when that list
     * is empty, so {@link PolicyCheck#policies} counts policies rather than names — the distinction
     * B1 turns on.
     *
     * @throws ProvisioningException if the catalog cannot be read; the message names the exact grant
     */
    private PolicyCheck legacyGranteesServing(final String database) {
        final Set<String> policies = new LinkedHashSet<>();
        final Set<String> named = new LinkedHashSet<>();
        boolean unenumerable = false;
        try (var rows = this.admin.queryRecords(
                "SELECT name AS p,"
                        + " toUInt8(apply_to_all != 0 OR length(apply_to_except) != 0) AS wide,"
                        + " arrayJoin(arrayPushBack(apply_to_list, '')) AS n"
                        + " FROM system.row_policies"
                        + " WHERE database = " + ProvisioningDdl.literal(database)
                        + " AND table IN (" + tableNames() + ") ORDER BY p, n").get()) {
            for (final var record : rows) {
                policies.add(record.getString("p"));
                unenumerable |= record.getLong("wide") == 1;
                final String name = record.getString("n");
                if (!name.isEmpty()) {
                    named.add(name);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException(policyProbeFailure(database, "interrupted"), e);
        } catch (final Exception e) {
            throw new ProvisioningException(policyProbeFailure(database, describe(e)), e);
        }
        return new PolicyCheck(policies.size(), unenumerable,
                named.stream().filter(name -> !name.contains("@")).sorted().toList());
    }

    /** The tables both probes filter on, as a SQL literal list. */
    private static String tableNames() {
        return ProvisioningDdl.legacyGrantTableNames().stream()
                .map(ProvisioningDdl::literal).collect(Collectors.joining(", "));
    }

    /** The privileges the revoke names, as a SQL literal list — the probe must see what it revokes. */
    private static String accessTypes() {
        return ProvisioningDdl.LEGACY_PRIVILEGES.stream()
                .map(ProvisioningDdl::literal).collect(Collectors.joining(", "));
    }

    private static String grantProbeFailure(final String database, final String cause) {
        return "could not read which grants the pre-rename roles hold on database '" + database
                + "': " + cause + ". Refusing to continue — nothing has been changed. ClickHouse"
                + " refuses this query outright rather than returning no rows, so continuing would"
                + " report 'nothing to revoke' on a database the pre-rename roles still reach."
                + " Grant the admin the privilege that answers the question and re-run:"
                + " GRANT SELECT ON system.grants TO <your --admin-user>";
    }

    private static String policyProbeFailure(final String database, final String cause) {
        return "could not check whether a pre-rename grantee still serves database '" + database
                + "': " + cause + ". Refusing to continue — nothing has been changed. The check reads"
                + " the row policies on that database's flows and rollup tables, and ClickHouse"
                + " refuses that query rather than filtering it — so 'no policy names a pre-rename"
                + " grantee' and 'I could not see the policies' are indistinguishable, and revoking on"
                + " the second takes a running collector's ingest away. Grant the admin the privilege"
                + " that answers the question and re-run:"
                + " GRANT SELECT ON system.row_policies TO <your --admin-user>";
    }

    private static String widerGrantFailure(final String database, final Map<String, String> wider) {
        final String held = wider.entrySet().stream()
                .map(entry -> "`" + entry.getKey() + "` on " + entry.getValue())
                .collect(Collectors.joining(", "));
        return "refusing to revoke on database '" + database + "': a pre-rename role holds a grant"
                + " WIDER than this command can take back — " + held + ". Nothing has been revoked."
                + " This revoke names only that database's flows and rollup tables, so running it"
                + " would leave the role holding everything else there while reporting the database"
                + " closed, and no re-run would ever be a no-op. Revoke the wider grant by hand, for"
                + " example: REVOKE " + String.join(", ", ProvisioningDdl.LEGACY_PRIVILEGES) + " ON "
                + wider.values().iterator().next() + " FROM `" + wider.keySet().iterator().next()
                + "` — then re-run this command.";
    }

    private static String noPolicyFailure(final String database) {
        return "refusing to revoke on database '" + database + "': it has NO row policies on its flows"
                + " or rollup tables, so there is nothing to reason from. Nothing has been revoked."
                + " The safety check asks which pre-rename grantees the policies still name; zero"
                + " policies is not 'nobody is served', it is 'I cannot tell' — and it is equally what"
                + " a hand-provisioned database, or one whose policies were dropped, looks like, where"
                + " a live collector may still be authenticating as a pre-rename account. Run onboard"
                + " for each tenant of this database first: that is what creates the policies this"
                + " check reads. If this database genuinely has no tenants, revoke by hand instead.";
    }

    private static String applyToAllFailure(final String database) {
        return "refusing to revoke on database '" + database + "': a row policy on its flows or rollup"
                + " tables applies to ALL principals (or to all except a listed few), so who depends on"
                + " this database cannot be enumerated from the policy grantees. Nothing has been"
                + " revoked. Such a policy leaves apply_to_list empty, which would otherwise read as"
                + " 'nobody is served' on a database where everyone is. Re-issue that policy naming"
                + " its grantees explicitly — onboard does — and run this again.";
    }

    private static String stillServedFailure(final String database, final List<String> blockers) {
        return "refusing to revoke the pre-rename roles' grants on database '" + database + "':"
                + " its row policies still name the database-unqualified grantee"
                + (blockers.size() == 1 ? " " : "s ") + String.join(", ", blockers)
                + ", so that database is not migrated yet and revoking now would take away access"
                + " still in use. Nothing has been revoked. A grantee carrying no '@" + database + "'"
                + " is a pre-rename account or a hand-added one — and it may be a ROLE rather than a"
                + " user, so check which before removing anything. For a pre-rename account, finish"
                + " the migration for its tenant: re-run onboard here, move that tenant's collector"
                + " and datasource to the '@" + database + "' account, retire the old grantee, then"
                + " re-run onboard once more so the policies stop naming it — and run this again.";
    }

    /**
     * What a revoke took back.
     *
     * @param roles      the pre-#649 roles that held a grant on this database and were revoked, writer
     *                   first. Empty means the probe looked and found nothing to revoke; it never
     *                   means "could not tell", because a probe that cannot tell throws.
     * @param tables     the qualified tables the revoke named, empty when {@code roles} is
     * @param statements the SQL, for {@code --dry-run} and for an operator who wants to see what ran
     */
    public record RevokeLegacyResult(List<String> roles, List<String> tables, List<String> statements) {
        public RevokeLegacyResult {
            roles = List.copyOf(roles);
            tables = List.copyOf(tables);
            statements = List.copyOf(statements);
        }
    }

    /**
     * The rollups {@code onboard} may repair in place, and a report of any it must not.
     *
     * <p>Uses {@link FlowsSchema#planRollupRepair}, the same decision the collector makes, so the
     * two paths cannot disagree about what is safe. A catalog it cannot read yields an empty plan
     * rather than an error: onboarding a tenant must not fail because a rollup could not be
     * inspected.</p>
     *
     * <p>Returns the whole plan, refusals included — the caller needs them to decide which views it
     * may re-point, and reading the catalog twice to answer that would invite the two answers to
     * disagree.</p>
     */
    private Optional<FlowsSchema.RepairPlan> plannedRollupRepair(final String database) {
        final Map<String, String> sortKeys = new LinkedHashMap<>();
        final Map<String, Set<String>> columns = new LinkedHashMap<>();
        try (var tables = this.admin.queryRecords("SELECT name AS n, sorting_key AS k FROM system.tables"
                + " WHERE database = '" + database + "'").get()) {
            tables.forEach(record -> sortKeys.put(record.getString("n"), record.getString("k")));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final Exception e) {
            // Logged, unlike before. Silence here meant a run that repaired nothing, created no
            // view and re-pointed none still printed the config stanza and exited 0 — while the
            // docs name re-running onboard as the remedy for exactly that state.
            log.warn("Could not read the rollup shapes in database '{}': {}. No rollup is repaired"
                    + " or created on this run; fix the admin's access to system.tables and re-run.",
                    database, e.getMessage());
            return Optional.empty();
        }
        try (var cols = this.admin.queryRecords("SELECT table AS t, name AS n FROM system.columns"
                + " WHERE database = '" + database + "'").get()) {
            cols.forEach(record -> columns
                    .computeIfAbsent(record.getString("t"), table -> new java.util.LinkedHashSet<>())
                    .add(record.getString("n")));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final Exception e) {
            log.warn("Could not read the rollup columns in database '{}': {}. No rollup is repaired"
                    + " or created on this run; fix the admin's access to system.columns and re-run.",
                    database, e.getMessage());
            return Optional.empty();
        }
        sortKeys.keySet().retainAll(FlowsSchema.rollupTableNames());

        final FlowsSchema.RepairPlan plan = FlowsSchema.planRollupRepair(sortKeys, columns);
        plan.refused().forEach((rollup, why) ->
                log.warn("Rollup {} left as it is: {}.", rollup, why));
        return Optional.of(plan);
    }

    /**
     * The config stanza plus whether this run created the schema — the caller needs the latter to
     * warn when an explicitly requested {@code --ttl-days} was not applied (table pre-existed).
     *
     * @param legacyAccounts the pre-#649 unqualified accounts for this tenant that are still live,
     *                       writer first. This run kept them on the row policies and did not drop
     *                       them, because a rolling upgrade is still authenticating as one; the
     *                       caller tells the operator how to finish. Empty means the probe looked
     *                       and found none — it never means "could not tell", because a probe that
     *                       cannot tell throws before this record is built.
     */
    public record OnboardResult(String configStanza, boolean schemaBootstrapped,
                                List<String> legacyAccounts) {
        public OnboardResult {
            legacyAccounts = List.copyOf(legacyAccounts);
        }
    }

    /**
     * Pre-flight existence check. {@code EXISTS DATABASE} is queried first — {@code EXISTS TABLE}
     * against a nonexistent database can raise {@code UNKNOWN_DATABASE} rather than returning 0.
     */
    private boolean flowsTableExists(final String database) {
        return exists("EXISTS DATABASE " + ProvisioningDdl.ident(database))
                && exists("EXISTS TABLE " + FlowsSchema.qualifiedFlows(database));
    }

    /**
     * The rollups whose view should be re-pointed, from {@link FlowsSchema#planViewRepair} — the
     * same decision the collector makes, on the same input.
     *
     * <p>A catalog it cannot read yields nothing to re-point rather than an error, for the same
     * reason the target plan does: onboarding a tenant must not fail because a view could not be
     * inspected.</p>
     */
    private Set<String> viewRepair(final String database, final Set<String> refused) {
        final Map<String, String> live = new LinkedHashMap<>();
        try (var views = this.admin.queryRecords("SELECT name AS n, as_select AS s FROM system.tables"
                + " WHERE database = '" + database + "'").get()) {
            views.forEach(record -> live.put(record.getString("n"), record.getString("s")));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (final Exception e) {
            log.warn("Could not read the rollup views in database '{}': {}. No view is re-pointed.",
                    database, e.getMessage());
            return Set.of();
        }
        final FlowsSchema.RepairPlan plan = FlowsSchema.planViewRepair(database, live, refused);
        plan.refused().forEach((rollup, why) -> log.warn("Rollup {} left as it is: {}.", rollup, why));
        return new LinkedHashSet<>(plan.repair());
    }

    /**
     * Whether every rollup target <em>and</em> its materialized view is present. Both halves are
     * checked because they fail independently: an interrupted bootstrap can leave the target table
     * created and the view not, which reports as a healthy-looking empty rollup.
     *
     * <p>Refused rollups are excluded from the question entirely. Their view is withheld on purpose,
     * so counting them would make this permanently false and turn every later run into a demand for
     * {@code --create-schema} that cannot help.</p>
     */
    private boolean rollupsExist(final String database, final Set<String> refused) {
        return FlowsSchema.rollupTableNames().stream()
                .filter(rollup -> !refused.contains(rollup))
                .allMatch(rollup -> exists("EXISTS TABLE " + FlowsSchema.qualifiedRollup(database, rollup))
                        && exists("EXISTS TABLE " + FlowsSchema.qualifiedRollupView(database, rollup)));
    }

    private boolean exists(final String sql) {
        try {
            // EXISTS returns a single UInt8 column named "result".
            return this.admin.queryAll(sql).getFirst().getLong("result") == 1;
        } catch (final Exception e) {
            throw new ProvisioningException("Pre-flight schema check failed: " + sql, e);
        }
    }

    /**
     * Drop the tenant's users — both the database-qualified accounts and the pre-#649 unqualified
     * ones — and its row policies. The per-database roles/constraints/quota are left intact.
     *
     * <p>The pre-rename pair is looked up <em>before</em> the drop so the caller can name what it
     * actually removed. That matters because those names carry no database: dropping them reaches
     * every database on the server, and an operator told "also dropped … if they existed" learns
     * neither whether anything was dropped nor whether another database just lost its credential.
     * Unlike {@code onboard}'s probe this one only shapes a message, so a refusal degrades to
     * {@link Optional#empty()} ("could not tell") rather than failing the teardown.
     */
    public OffboardResult offboard(final TenantRef ref) {
        Optional<List<String>> legacy;
        try {
            legacy = Optional.of(readLiveLegacyAccounts(ref.tenant()));
        } catch (final ProvisioningException e) {
            log.warn("Could not determine which pre-rename accounts of tenant '{}' this offboard"
                    + " removes: {}", ref.tenant(), describe(e));
            legacy = Optional.empty();
        }
        execute(ProvisioningDdl.offboardTenant(ref.database(), ref.tenant()));
        return new OffboardResult(legacy);
    }

    /**
     * What a teardown removed beyond the database-qualified pair.
     *
     * @param legacyDropped the pre-#649 unqualified accounts that existed and were therefore
     *                      dropped. Empty list means the probe looked and found none, so there is
     *                      nothing to warn about; {@link Optional#empty()} means it could not look,
     *                      which is a different statement and must not be reported as "none".
     */
    public record OffboardResult(Optional<List<String>> legacyDropped) {
    }

    private void execute(final List<String> statements) {
        for (final String sql : statements) {
            try {
                this.admin.execute(sql).get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisioningException("Interrupted while provisioning", e);
            } catch (final ExecutionException e) {
                throw new ProvisioningException("Provisioning statement failed: " + redact(sql), e.getCause());
            }
        }
    }

    private String resolve(final String ref) {
        return this.secretResolvers.resolve(SecretRef.of(ref));
    }

    private static String configStanza(final TenantSpec spec) {
        // Built by concatenation (not String.format) so the line separators stay literal '\n' — a
        // config stanza the operator pastes, not platform-dependent output.
        return "riptide.clickhouse.username=" + ProvisioningDdl.writerUser(spec.tenant(), spec.database()) + "\n"
                + "riptide.clickhouse.password=" + spec.writerSecret() + "\n"
                + "riptide.identity.tenant=" + spec.tenant() + "\n"
                + "riptide.identity.organisation=" + spec.organisation();
    }

    /**
     * Never surface a resolved password in an error. Matches the whole escaped string literal after
     * {@code IDENTIFIED WITH … BY}: {@code \\.} consumes an escaped char and {@code [^'\\]} any
     * other (including newlines). The two branches are disjoint (the "other" branch excludes the
     * backslash), so there is no ambiguity that could cause catastrophic backtracking, and a
     * password with a {@code '} or {@code \n} cannot leak past a naive {@code '.*?'}.
     */
    static String redact(final String sql) {
        return sql.replaceAll("(?is)(IDENTIFIED WITH \\w+ BY )'(?:\\\\.|[^'\\\\])*'", "$1'***'");
    }

    /** A validated reference to an existing tenant, for teardown. */
    public record TenantRef(String database, String tenant) {
        public TenantRef {
            TenantSpec.requireSafe("database", database);
            TenantSpec.requireSafe("tenant", tenant);
        }
    }

    /** Thrown when a provisioning statement fails; the message never contains a resolved secret. */
    public static final class ProvisioningException extends RuntimeException {
        public ProvisioningException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
