/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.junit.jupiter.api.Test;
import org.riptide.schema.FlowsSchema;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provisioning SQL is generated, so its escaping and shape are unit-checked here — the live
 * behaviour is proven in {@code TenantOnboardingIT}. The load-bearing property is that a resolved
 * password lands as a properly-escaped literal and generated identifiers are backtick-quoted.
 */
class ProvisioningDdlTest {

    @Test
    void literalEscapesQuoteAndBackslash() {
        assertThat(ProvisioningDdl.literal("s3cr3t")).isEqualTo("'s3cr3t'");
        assertThat(ProvisioningDdl.literal("a'b")).isEqualTo("'a\\'b'");
        assertThat(ProvisioningDdl.literal("a\\b")).isEqualTo("'a\\\\b'");
    }

    @Test
    void identIsBacktickQuoted() {
        assertThat(ProvisioningDdl.ident("writer_acme")).isEqualTo("`writer_acme`");
    }

    /**
     * {@code ident} must keep rejecting the delimiter. {@code @} is introduced only by
     * {@link ProvisioningDdl#qualified}, from components each validated on their own — accepting it
     * from input would make the split point forgeable and the composition no longer injective.
     */
    @Test
    void identRejectsTheQualifierDelimiter() {
        assertThatThrownBy(() -> ProvisioningDdl.ident("writer_acme@riptide"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisioningDdl.writerUser("ac@me", "riptide"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisioningDdl.writerUser("acme", "ript'ide"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The property the {@code @} delimiter exists for (#649): {@code (tenant, database)} maps to a
     * distinct name for every pair. An inequality between two computed names, deliberately — a test
     * that spelled out the expected strings would move with the naming instead of pinning it, and
     * would still pass under a {@code _} delimiter, which is exactly the collision being ruled out.
     */
    @Test
    void qualifyingIsInjectiveOverTheTenantDatabaseSplit() {
        // The collision the delimiter has to rule out is in the tenant/database join, and it is
        // narrower than it looks: the pair must differ only in where the split falls. `_` is legal
        // in both components, so under `<tenant>_<database>` both of these spell
        // `writer_foo_bar_baz` and the second onboarding would take over the first's account.
        assertThat(ProvisioningDdl.writerUser("foo", "bar_baz"))
                .isNotEqualTo(ProvisioningDdl.writerUser("foo_bar", "baz"));
        assertThat(ProvisioningDdl.readerUser("foo", "bar_baz"))
                .isNotEqualTo(ProvisioningDdl.readerUser("foo_bar", "baz"));

        // Same tenant, two databases: two accounts, so the second onboarding cannot rotate the
        // first one's password. (Observed against a server in TenantOnboardingIT.)
        assertThat(ProvisioningDdl.writerUser("acme", "db_a"))
                .isNotEqualTo(ProvisioningDdl.writerUser("acme", "db_b"));
        // ...and a writer and a reader are never the same account either.
        assertThat(ProvisioningDdl.writerUser("acme", "db_a"))
                .isNotEqualTo(ProvisioningDdl.readerUser("acme", "db_a"));
    }

    @Test
    void ensureSharedHasRolesConstraintsAndKeyedQuota() {
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 50_000_000_000L);
        assertThat(sql).anyMatch(s -> s.contains("CREATE ROLE IF NOT EXISTS `flow_writer@riptide`"));
        assertThat(sql).anyMatch(s ->
                s.contains("ALTER ROLE `flow_reader@riptide` SETTINGS readonly = 2, allow_ddl = 0"));
        assertThat(sql).anyMatch(s -> s.contains("ADD CONSTRAINT IF NOT EXISTS tenant_pinned"));
        assertThat(sql).anyMatch(s -> s.contains("KEYED BY user_name TO `flow_writer@riptide`"));
        assertThat(sql).anyMatch(s -> s.contains("MAX written_bytes = 50000000000"));
    }

    /**
     * Every instance-wide object carries its database (#649). Users, roles and quotas share one flat
     * namespace across the server, so an unqualified role is one role for every database on it — and
     * that is the privilege that let {@code writer_acme} in {@code db_b} insert into {@code db_a}.
     * Asserted as "no statement names an unqualified one", not as a name list, so a role added later
     * cannot slip through unqualified.
     */
    @Test
    void everyInstanceWideObjectIsQualifiedByItsDatabase() {
        final List<String> sql = ProvisioningDdl.ensureShared("db_a", 1L);
        assertThat(sql)
                .as("an unqualified role or quota is shared by every database on the instance")
                .noneMatch(s -> s.contains("`flow_writer`") || s.contains("`flow_reader`")
                        || s.contains("`flow_ingest`")
                        || s.contains(" flow_writer") || s.contains(" flow_reader")
                        || s.contains(" flow_ingest"));
        assertThat(sql).anyMatch(s -> s.contains("CREATE QUOTA IF NOT EXISTS `flow_ingest@db_a`"));
        // The bodies are unchanged; only the names moved. A second database gets its own set.
        assertThat(ProvisioningDdl.ensureShared("db_b", 1L))
                .noneMatch(s -> s.contains("@db_a`"));
    }

    @Test
    void bootstrapSchemaCreatesDatabaseThenTableWithTtl() {
        final List<String> sql = ProvisioningDdl.bootstrapSchema("riptide", 400);
        // Database before table. The composed onboard ordering (bootstrap before the GRANT/ALTER
        // that need the table) is pinned end-to-end by TenantOnboardingIT against a fresh server.
        assertThat(sql.get(0)).isEqualTo("CREATE DATABASE IF NOT EXISTS `riptide`");
        assertThat(sql.get(1).strip()).startsWith("CREATE TABLE IF NOT EXISTS `riptide`.flows (");
        assertThat(sql.get(1)).contains("TTL toDateTime(timestamp) + INTERVAL 400 DAY");
        assertThat(sql).hasSize(2);
    }

    @Test
    void ensureSharedUpgradesAdditiveColumnsFirst() {
        // Additive column upgrades are emitted on every run (before the grants, same
        // table-exists precondition) so re-running onboard upgrades a pre-existing table in place.
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 50_000_000_000L);
        final int additive = FlowsSchema.additiveColumnNames().size();
        assertThat(sql.get(0)).isEqualTo(
                "ALTER TABLE `riptide`.flows ADD COLUMN IF NOT EXISTS srcCountry LowCardinality(String)");
        // Counted off the additive set rather than a literal, so adding a column here is a
        // one-line change in FlowsSchema and not a test edit.
        assertThat(sql.subList(0, additive)).allMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS"));
        assertThat(sql).filteredOn(s -> s.contains("ADD COLUMN")).hasSize(additive);
        assertThat(sql.get(additive - 1)).contains("samplingProvenance LowCardinality(String)");
    }

    @Test
    void bootstrapSchemaIncludesAdditiveColumns() {
        assertThat(ProvisioningDdl.bootstrapSchema("riptide", 30).get(1))
                .contains("srcCountry LowCardinality(String)")
                .contains("dstCity LowCardinality(String)")
                .contains("exporterName LowCardinality(String)")
                .contains("samplingProvenance LowCardinality(String)");
    }

    @Test
    void ensureSharedEmitsNoCreateStatement() {
        // ClickHouse checks CREATE privileges even when IF NOT EXISTS would no-op, so a default
        // (least-privilege) onboard run must never send CREATE DATABASE/CREATE TABLE — the schema
        // bootstrap is a separate, opt-in recipe.
        assertThat(ProvisioningDdl.ensureShared("riptide", 50_000_000_000L))
                .noneMatch(s -> s.startsWith("CREATE DATABASE") || s.startsWith("CREATE TABLE"));
    }

    @Test
    void neitherRecipeCreatesTheSamplesView() {
        // In provisioned mode flow_reader is not granted SELECT on samples, so onboard must not
        // create the (inert, unqueryable) view — it stays a manage-mode-only convenience.
        assertThat(ProvisioningDdl.bootstrapSchema("riptide", 30)).noneMatch(s -> s.contains("samples"));
        assertThat(ProvisioningDdl.ensureShared("riptide", 50_000_000_000L))
                .noneMatch(s -> s.contains("samples"));
    }

    @Test
    void onboardTenantScopesUsersPolicyWithEscapedPassword() {
        final List<String> sql = ProvisioningDdl.onboardTenant("riptide", "acme", "acme-eu", "p'w", "r'w", List.of());
        // The six user/grant statements, the flows policy, then one policy per rollup.
        assertThat(sql).hasSize(7 + FlowsSchema.rollupTableNames().size());
        assertThat(sql.get(0))
                .contains("CREATE USER IF NOT EXISTS `writer_acme@riptide`")
                .contains("IDENTIFIED WITH sha256_password BY 'p\\'w'")
                .contains("SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST");
        // Password reconciled with ALTER USER so a re-run after rotation updates the credential.
        assertThat(sql.get(1))
                .isEqualTo("ALTER USER `writer_acme@riptide` IDENTIFIED WITH sha256_password BY 'p\\'w'");
        assertThat(sql.get(2)).isEqualTo("GRANT `flow_writer@riptide` TO `writer_acme@riptide`");
        assertThat(sql.get(4))
                .isEqualTo("ALTER USER `bi_acme@riptide` IDENTIFIED WITH sha256_password BY 'r\\'w'");
        // The row policy keeps its unqualified name: its identity is `name ON db.table`, so it is
        // already scoped by the table it hangs on.
        assertThat(sql.get(6))
                .contains("CREATE ROW POLICY OR REPLACE `acme_iso` ON `riptide`.flows")
                .contains("USING tenant = 'acme' TO `bi_acme@riptide`");
    }

    @Test
    void bootstrapRollupsUpgradesAdditiveColumnsThenCreatesTargets() {
        // The rollups select the additive columns, so those come first.
        final List<String> sql = ProvisioningDdl.bootstrapRollups("riptide");
        final int additive = FlowsSchema.additiveColumnNames().size();
        final int rollups = FlowsSchema.rollupTableNames().size();
        assertThat(sql).hasSize(additive + rollups);
        assertThat(sql.subList(0, additive))
                .allSatisfy(s -> assertThat(s).contains("ADD COLUMN IF NOT EXISTS"));
        assertThat(sql.subList(additive, sql.size()))
                .allSatisfy(s -> assertThat(s).startsWith("CREATE TABLE IF NOT EXISTS"));
    }

    /**
     * Views are a separate step so they can be emitted after the repair. Creating them first aborts
     * an onboard run against a stale target, because {@code CREATE … IF NOT EXISTS} validates its
     * SELECT even when it no-ops — and that is the one path a provisioned deployment has to fix
     * exactly that state.
     */
    @Test
    void rollupViewsAreASeparateStepFromTheTargets() {
        assertThat(ProvisioningDdl.bootstrapRollups("riptide"))
                .as("no view may be created before the repair has run")
                .noneMatch(s -> s.startsWith("CREATE MATERIALIZED VIEW"));
        assertThat(ProvisioningDdl.bootstrapRollupViews("riptide", Set.of()))
                .hasSize(FlowsSchema.rollupTableNames().size())
                .allSatisfy(s -> assertThat(s).startsWith("CREATE MATERIALIZED VIEW IF NOT EXISTS"));
    }

    /**
     * A refused rollup gets no view.
     *
     * <p>Its target is not being repaired, so it still lacks the dimension the view's SELECT names,
     * and {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} validates that SELECT even when it no-ops:
     * the statement fails with {@code THERE_IS_NO_COLUMN} and takes the whole onboard run with it.
     * Losing the roles, users and password rotation because one rollup was deliberately left alone
     * is a far worse trade than leaving that rollup empty — which is what refusing it already
     * meant.</p>
     */
    @Test
    void aRefusedRollupGetsNoView() {
        final String refused = FlowsSchema.rollupTableNames().getFirst();

        final List<String> sql = ProvisioningDdl.bootstrapRollupViews("riptide", Set.of(refused));

        assertThat(sql).hasSize(FlowsSchema.rollupTableNames().size() - 1);
        assertThat(sql).noneMatch(s -> s.contains(refused + "_mv"));
    }

    /**
     * A provisioned deployment that already has rollups is the case {@code CREATE … IF NOT EXISTS}
     * cannot reach, and its collector runs in validate mode and issues no DDL — so re-running
     * onboard is the only path it has (#470).
     */
    @Test
    void repairRollupsAltersTheTargetBeforeItsView() {
        final List<String> rollups = FlowsSchema.rollupTableNames();
        final List<String> sql = ProvisioningDdl.repairRollups("riptide", rollups, Set.copyOf(rollups));

        assertThat(sql).hasSize(rollups.size() * 2);
        assertThat(sql.subList(0, rollups.size()))
                .as("every target is repaired before any view names the columns it adds")
                .allSatisfy(s -> assertThat(s).startsWith("ALTER TABLE").contains("MODIFY ORDER BY"));
        assertThat(sql.subList(rollups.size(), sql.size()))
                .allSatisfy(s -> assertThat(s).startsWith("ALTER TABLE").contains("MODIFY QUERY"));
    }

    /** Nothing planned, nothing emitted — the guard decides, not this method. */
    @Test
    void repairRollupsEmitsNothingForAnEmptyPlan() {
        assertThat(ProvisioningDdl.repairRollups("riptide", List.of(), Set.of())).isEmpty();
    }

    /**
     * A rollup whose view does not exist gets its target repaired and no {@code MODIFY QUERY}.
     *
     * <p>The plan comes from target shapes alone, so a half-provisioned database can plan a repair
     * for a rollup that has no view at all. {@code MODIFY QUERY} against a missing view fails with
     * {@code UNKNOWN_TABLE} and aborts the entire onboard run — including the {@code CREATE} that
     * would have built the view correctly moments later, which is the only way out of that state.</p>
     */
    @Test
    void repairRollupsSkipsTheViewRepairWhereTheViewDoesNotExist() {
        final List<String> rollups = FlowsSchema.rollupTableNames();
        final String orphan = rollups.getFirst();
        final Set<String> present = rollups.stream().skip(1).collect(Collectors.toSet());

        final List<String> sql = ProvisioningDdl.repairRollups("riptide", rollups, present);

        assertThat(sql.stream().filter(s -> s.contains("MODIFY ORDER BY")))
                .as("every planned target is still repaired")
                .hasSize(rollups.size());
        assertThat(sql)
                .as("but the rollup with no view is not sent a MODIFY QUERY")
                .noneMatch(s -> s.contains("MODIFY QUERY") && s.contains(orphan + "_mv"));
        assertThat(sql.stream().filter(s -> s.contains("MODIFY QUERY")))
                .hasSize(rollups.size() - 1);
    }

    @Test
    void ensureSharedGrantsEveryRollupToBothRoles() {
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 1L);
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String table = FlowsSchema.qualifiedRollup("riptide", rollup);
            assertThat(sql).contains("GRANT INSERT ON " + table + " TO `flow_writer@riptide`");
            assertThat(sql).contains("GRANT SELECT ON " + table + " TO `flow_reader@riptide`");
        }
    }

    /**
     * The writer verifies rollup shapes at startup (#470), and ClickHouse filters
     * {@code system.tables} by access <em>silently</em> — without a grant on the view it reads zero
     * rows, which is indistinguishable from the view not existing. The check would then be unable
     * to tell a stale rollup from an unreadable one on every deployment.
     */
    @Test
    void writerGetsShowButNotSelectOnEveryRollupView() {
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 1L);
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String mv = FlowsSchema.qualifiedRollupView("riptide", rollup);
            assertThat(sql).contains("GRANT SHOW TABLES ON " + mv + " TO `flow_writer@riptide`");
            // SELECT would be a cross-tenant read path. The writer role is per-database now, but it
            // is still shared by every tenant IN that database, and a row policy on the rollup
            // target does NOT apply through the view's name: probed on 26.7, a writer holding
            // SELECT on the _mv read every tenant's rows while being denied outright on the target
            // the policy is attached to.
            assertThat(sql)
                    .as("SELECT on a rollup view bypasses the target's row policy")
                    .doesNotContain("GRANT SELECT ON " + mv + " TO `flow_writer@riptide`");
        }
    }

    @Test
    void writerGetsSelectOnFlowsSoTheRollupViewsCanPush() {
        // A materialized view runs as the inserting user: without SELECT on the source table the
        // rollups would silently receive nothing.
        assertThat(ProvisioningDdl.ensureShared("riptide", 1L))
                .contains("GRANT SELECT ON `riptide`.flows TO `flow_writer@riptide`");
    }

    @Test
    void rowPoliciesCoverFlowsAndEveryRollupWithOneSharedPredicate() {
        final List<String> sql = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r", List.of());
        final List<String> policies = sql.stream().filter(s -> s.contains("ROW POLICY")).toList();
        assertThat(policies).hasSize(1 + FlowsSchema.rollupTableNames().size());
        assertThat(policies).allSatisfy(policy -> assertThat(policy)
                .startsWith("CREATE ROW POLICY OR REPLACE `acme_iso` ON ")
                .contains("FOR SELECT USING tenant = 'acme'"));
        // The writer is named on the flows policy to CONSTRAIN it, not to enable it. A policy is
        // not deny-by-default for a user it does not name: the pinned image ships
        // users_without_row_policies_can_read_rows=true, so an unnamed writer holding SELECT on
        // flows would read every tenant's rows rather than starve. Naming it pins it to its own.
        // It is absent from the rollup policies because it holds no SELECT there at all, so being
        // unnamed costs and exposes nothing — it reaches a rollup only by INSERT.
        assertThat(policies.getFirst())
                .contains("ON `riptide`.flows ")
                .endsWith("TO `bi_acme@riptide`, `writer_acme@riptide`");
        assertThat(policies.subList(1, policies.size()))
                .allSatisfy(policy -> assertThat(policy).endsWith("TO `bi_acme@riptide`"));
    }

    /**
     * A live pre-rename account keeps its place on the policies this recipe replaces.
     *
     * <p>The policy name is unchanged by the rename, so {@code OR REPLACE} rewrites the existing
     * policy's {@code TO} list. Dropping a still-live legacy account from it leaves that account
     * named by no policy on the table — which, since the pinned image ships
     * {@code users_without_row_policies_can_read_rows=true}, is a cross-tenant read and not a blank
     * dashboard. Proven end to end against a server in
     * {@code TenantOnboardingIT#onboardKeepsALiveLegacyAccountOnTheRowPoliciesItReplaces}; pinned
     * here at the statement level, including the split the two policy kinds make.</p>
     */
    @Test
    void aLiveLegacyAccountStaysNamedOnThePoliciesItAlreadyHeld() {
        final List<String> policies = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r",
                        List.of("writer_acme", "bi_acme")).stream()
                .filter(s -> s.contains("ROW POLICY")).toList();

        assertThat(policies.getFirst())
                .as("flows named both legacy accounts before the rename, so both stay")
                .contains("ON `riptide`.flows ")
                .endsWith("TO `bi_acme@riptide`, `writer_acme@riptide`, `bi_acme`, `writer_acme`");
        assertThat(policies.subList(1, policies.size()))
                .as("a rollup policy named only the reader, and only the reader can read one")
                .allSatisfy(policy -> assertThat(policy).endsWith("TO `bi_acme@riptide`, `bi_acme`"));
    }

    /**
     * Only accounts observed live may be named. {@code CREATE ROW POLICY … TO} a user that does not
     * exist fails the statement and takes the whole onboard with it, so a legacy account absent from
     * the probe's result must not appear — this is why the probe runs before the statements are
     * built rather than after they run.
     */
    @Test
    void aLegacyAccountThatIsNotLiveIsNeverNamed() {
        final List<String> policies = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r",
                        List.of("bi_acme")).stream()
                .filter(s -> s.contains("ROW POLICY")).toList();

        assertThat(policies.getFirst()).endsWith("TO `bi_acme@riptide`, `writer_acme@riptide`, `bi_acme`");
        assertThat(policies)
                .as("the writer was not live, so nothing may name it")
                .noneMatch(policy -> policy.endsWith("`writer_acme`") || policy.contains("`writer_acme`,"));
    }

    @Test
    void writerPolicyPredicateMatchesTheTenantPinnedConstraint() {
        // The policy must not widen what the CHECK barrier already pins, or the writer could read
        // rows it cannot write.
        final String constraint = ProvisioningDdl.ensureShared("riptide", 1L).stream()
                .filter(s -> s.contains("tenant_pinned")).findFirst().orElseThrow();
        assertThat(constraint).contains("CHECK tenant = getSetting('SQL_tenant')");
        final String policy = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r", List.of()).stream()
                .filter(s -> s.contains("ROW POLICY") && s.contains("`riptide`.flows "))
                .findFirst().orElseThrow();
        assertThat(policy).contains("USING tenant = 'acme'");
        assertThat(ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r", List.of()))
                .anyMatch(s -> s.contains("SETTINGS SQL_tenant = 'acme' CONST"));
    }

    @Test
    void offboardDropsThePolicyFromFlowsAndEveryRollup() {
        // A policy left on a rollup would keep denying rows there after the tenant is gone.
        final List<String> sql = ProvisioningDdl.offboardTenant("riptide", "acme");
        assertThat(sql).contains("DROP ROW POLICY IF EXISTS `acme_iso` ON `riptide`.flows");
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            assertThat(sql).contains("DROP ROW POLICY IF EXISTS `acme_iso` ON "
                    + FlowsSchema.qualifiedRollup("riptide", rollup));
        }
        assertThat(sql).contains("DROP USER IF EXISTS `bi_acme@riptide`");
        assertThat(sql).contains("DROP USER IF EXISTS `writer_acme@riptide`");
    }

    /**
     * Offboard drops the pre-#649 unqualified accounts too.
     *
     * <p>Without this the rename turns an upgrade into a silently failed revocation: on an instance
     * onboarded before it, the credential that actually authenticates is {@code writer_acme}, and
     * dropping only {@code writer_acme@riptide} would leave it live while the CLI reported the
     * tenant revoked. The legacy roles are deliberately left alone — they are instance-wide and may
     * still be granted to another tenant's legacy user.
     */
    @Test
    void offboardAlsoDropsThePreRenameAccounts() {
        final List<String> sql = ProvisioningDdl.offboardTenant("riptide", "acme");
        assertThat(sql).contains("DROP USER IF EXISTS `writer_acme`");
        assertThat(sql).contains("DROP USER IF EXISTS `bi_acme`");
        assertThat(sql)
                .as("the legacy roles may still be granted to another tenant's legacy user")
                .noneMatch(s -> s.startsWith("DROP ROLE"));
    }

    /**
     * The row policies keep their unqualified name, on purpose.
     *
     * <p>A policy's identity is {@code name ON db.table} — {@code system.row_policies.name} holds
     * the full form, and {@code acme_iso ON db_a.flows} / {@code acme_iso ON db_b.flows} coexist as
     * two objects (verified on 26.7). Qualifying the short name would buy no isolation and cost
     * every upgraded deployment a migration, so this pins the decision against a later "fix".
     */
    @Test
    void rowPolicyNamesStayUnqualifiedBecauseTheirTableAlreadyScopesThem() {
        assertThat(ProvisioningDdl.onboardTenant("db_a", "acme", "org1", "w", "r", List.of()))
                .filteredOn(s -> s.contains("ROW POLICY"))
                .allSatisfy(s -> assertThat(s).contains("`acme_iso` ON `db_a`."));
        assertThat(ProvisioningDdl.onboardTenant("db_b", "acme", "org1", "w", "r", List.of()))
                .filteredOn(s -> s.contains("ROW POLICY"))
                .allSatisfy(s -> assertThat(s).contains("`acme_iso` ON `db_b`."));
    }
}
