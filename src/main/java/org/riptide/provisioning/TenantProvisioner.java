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
