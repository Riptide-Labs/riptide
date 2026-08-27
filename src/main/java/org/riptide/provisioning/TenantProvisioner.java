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

/**
 * Provisions and de-provisions a tenant against ClickHouse using an admin connection. This is the
 * whole admin-side of multi-tenancy: it ensures the one-time role/constraint/quota objects, then
 * creates (or drops) the per-tenant scoped users and row policy. It depends only on a ClickHouse
 * {@link Client} and {@link SecretResolvers}, so it carries no Spring coupling and lifts cleanly
 * into a future {@code riptide-admin} module.
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
     * shared roles granted on it. The bootstrap statements are only <em>emitted</em> when actually
     * creating: ClickHouse checks the {@code CREATE} privileges even when {@code IF NOT EXISTS}
     * would no-op, and a least-privilege admin re-run (e.g. password rotation) must keep working.
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
        statements.addAll(ProvisioningDdl.onboardTenant(
                spec.database(), spec.tenant(), spec.organisation(), writerPassword, readerPassword));
        execute(statements);

        return new OnboardResult(configStanza(spec), bootstrap);
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
        if (database == null || !database.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid database name");
        }
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
     */
    public record OnboardResult(String configStanza, boolean schemaBootstrapped) {
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
        if (database == null || !database.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid database name");
        }
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

    /** Drop the tenant's users and row policy; the shared roles/constraints/quota are left intact. */
    public void offboard(final TenantRef ref) {
        execute(ProvisioningDdl.offboardTenant(ref.database(), ref.tenant()));
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
        return "riptide.clickhouse.username=writer_" + spec.tenant() + "\n"
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
