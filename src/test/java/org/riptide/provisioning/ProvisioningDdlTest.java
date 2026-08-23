/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.junit.jupiter.api.Test;
import org.riptide.schema.FlowsSchema;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void ensureSharedHasRolesConstraintsAndKeyedQuota() {
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 50_000_000_000L);
        assertThat(sql).anyMatch(s -> s.contains("CREATE ROLE IF NOT EXISTS flow_writer"));
        assertThat(sql).anyMatch(s -> s.contains("ALTER ROLE flow_reader SETTINGS readonly = 2, allow_ddl = 0"));
        assertThat(sql).anyMatch(s -> s.contains("ADD CONSTRAINT IF NOT EXISTS tenant_pinned"));
        assertThat(sql).anyMatch(s -> s.contains("KEYED BY user_name TO flow_writer"));
        assertThat(sql).anyMatch(s -> s.contains("MAX written_bytes = 50000000000"));
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
        final List<String> sql = ProvisioningDdl.onboardTenant("riptide", "acme", "acme-eu", "p'w", "r'w");
        // The six user/grant statements, the flows policy, then one policy per rollup.
        assertThat(sql).hasSize(7 + FlowsSchema.rollupTableNames().size());
        assertThat(sql.get(0))
                .contains("CREATE USER IF NOT EXISTS `writer_acme`")
                .contains("IDENTIFIED WITH sha256_password BY 'p\\'w'")
                .contains("SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST");
        // Password reconciled with ALTER USER so a re-run after rotation updates the credential.
        assertThat(sql.get(1)).isEqualTo("ALTER USER `writer_acme` IDENTIFIED WITH sha256_password BY 'p\\'w'");
        assertThat(sql.get(2)).isEqualTo("GRANT flow_writer TO `writer_acme`");
        assertThat(sql.get(4)).isEqualTo("ALTER USER `bi_acme` IDENTIFIED WITH sha256_password BY 'r\\'w'");
        assertThat(sql.get(6))
                .contains("CREATE ROW POLICY OR REPLACE `acme_iso` ON `riptide`.flows")
                .contains("USING tenant = 'acme' TO `bi_acme`");
    }

    @Test
    void bootstrapRollupsUpgradesAdditiveColumnsThenCreatesTargetsBeforeViews() {
        // The ordering is load-bearing, not cosmetic, at three points: the rollups select the
        // additive columns, a view needs its TO target, and a repaired view's SELECT names columns
        // the target ALTER adds. Getting any of them backwards fails against a real server.
        final List<String> sql = ProvisioningDdl.bootstrapRollups("riptide");
        final int additive = FlowsSchema.additiveColumnNames().size();
        final int rollups = FlowsSchema.rollupTableNames().size();
        assertThat(sql).hasSize(additive + rollups * 4);

        final int targets = additive + rollups;
        final int alters = targets + rollups;
        final int views = alters + rollups;
        assertThat(sql.subList(0, additive))
                .allSatisfy(s -> assertThat(s).contains("ADD COLUMN IF NOT EXISTS"));
        assertThat(sql.subList(additive, targets))
                .allSatisfy(s -> assertThat(s).startsWith("CREATE TABLE IF NOT EXISTS"));
        assertThat(sql.subList(targets, alters))
                .as("targets are repaired before any view references the columns")
                .allSatisfy(s -> assertThat(s).startsWith("ALTER TABLE").contains("MODIFY ORDER BY"));
        assertThat(sql.subList(alters, views))
                .allSatisfy(s -> assertThat(s).startsWith("CREATE MATERIALIZED VIEW IF NOT EXISTS"));
        assertThat(sql.subList(views, sql.size()))
                .allSatisfy(s -> assertThat(s).startsWith("ALTER TABLE").contains("MODIFY QUERY"));
    }

    /**
     * A provisioned deployment that already has rollups is the case {@code CREATE … IF NOT EXISTS}
     * cannot reach, so re-running onboard must carry the repair (#470).
     */
    @Test
    void bootstrapRollupsRepairsRollupsThatAlreadyExist() {
        final List<String> sql = ProvisioningDdl.bootstrapRollups("riptide");
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String target = FlowsSchema.qualifiedRollup("riptide", rollup);
            assertThat(sql).anySatisfy(s -> assertThat(s)
                    .startsWith("ALTER TABLE " + target).contains("MODIFY ORDER BY"));
            assertThat(sql).anySatisfy(s -> assertThat(s)
                    .startsWith("ALTER TABLE " + FlowsSchema.qualifiedRollupView("riptide", rollup))
                    .contains("MODIFY QUERY"));
        }
    }

    @Test
    void ensureSharedGrantsEveryRollupToBothRoles() {
        final List<String> sql = ProvisioningDdl.ensureShared("riptide", 1L);
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            final String table = FlowsSchema.qualifiedRollup("riptide", rollup);
            assertThat(sql).contains("GRANT INSERT ON " + table + " TO flow_writer");
            assertThat(sql).contains("GRANT SELECT ON " + table + " TO flow_reader");
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
            assertThat(sql).contains("GRANT SHOW TABLES ON " + mv + " TO flow_writer");
            // SELECT would be a cross-tenant read path. flow_writer is shared by every per-tenant
            // writer, and a row policy on the rollup target does NOT apply through the view's name:
            // probed on 26.7, a writer holding SELECT on the _mv read every tenant's rows while
            // being denied outright on the target the policy is attached to.
            assertThat(sql)
                    .as("SELECT on a rollup view bypasses the target's row policy")
                    .doesNotContain("GRANT SELECT ON " + mv + " TO flow_writer");
        }
    }

    @Test
    void writerGetsSelectOnFlowsSoTheRollupViewsCanPush() {
        // A materialized view runs as the inserting user: without SELECT on the source table the
        // rollups would silently receive nothing.
        assertThat(ProvisioningDdl.ensureShared("riptide", 1L))
                .contains("GRANT SELECT ON `riptide`.flows TO flow_writer");
    }

    @Test
    void rowPoliciesCoverFlowsAndEveryRollupWithOneSharedPredicate() {
        final List<String> sql = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r");
        final List<String> policies = sql.stream().filter(s -> s.contains("ROW POLICY")).toList();
        assertThat(policies).hasSize(1 + FlowsSchema.rollupTableNames().size());
        assertThat(policies).allSatisfy(policy -> assertThat(policy)
                .startsWith("CREATE ROW POLICY OR REPLACE `acme_iso` ON ")
                .contains("FOR SELECT USING tenant = 'acme'"));
        // The writer is named on the flows policy (deny-by-default would otherwise starve the
        // rollup views) but not on the rollups, which it only ever reaches by INSERT.
        assertThat(policies.getFirst())
                .contains("ON `riptide`.flows ")
                .endsWith("TO `bi_acme`, `writer_acme`");
        assertThat(policies.subList(1, policies.size()))
                .allSatisfy(policy -> assertThat(policy).endsWith("TO `bi_acme`"));
    }

    @Test
    void writerPolicyPredicateMatchesTheTenantPinnedConstraint() {
        // The policy must not widen what the CHECK barrier already pins, or the writer could read
        // rows it cannot write.
        final String constraint = ProvisioningDdl.ensureShared("riptide", 1L).stream()
                .filter(s -> s.contains("tenant_pinned")).findFirst().orElseThrow();
        assertThat(constraint).contains("CHECK tenant = getSetting('SQL_tenant')");
        final String policy = ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r").stream()
                .filter(s -> s.contains("ROW POLICY") && s.contains("`riptide`.flows "))
                .findFirst().orElseThrow();
        assertThat(policy).contains("USING tenant = 'acme'");
        assertThat(ProvisioningDdl.onboardTenant("riptide", "acme", "org1", "w", "r"))
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
        assertThat(sql.get(sql.size() - 2)).isEqualTo("DROP USER IF EXISTS `bi_acme`");
        assertThat(sql.getLast()).isEqualTo("DROP USER IF EXISTS `writer_acme`");
    }
}
