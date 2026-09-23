---
title: Multi-tenancy
sidebar_position: 9
description: How many riptide processes write into one ClickHouse cluster with hard isolation between tenants, what the write barrier and the row policies guarantee, and why every provisioned object carries its database.
---

# How multi-tenancy works

Many riptide processes, one per isolated network or several per network, write into one ClickHouse cluster.
Isolation between tenants and organisations is hard, enforced by the server on both the write and the read side.
Filtering by zone and system is soft.
A monitoring provider can collect NetFlow, IPFIX and sFlow from many customers' isolated networks, overlapping RFC 1918 space included, and keep each customer's data provably separate.

The procedure is [Onboard a tenant](../guides/onboard-a-tenant.md); the flags and the DDL are in the [provisioning CLI reference](../reference/provisioning-cli.md).

## The identity model {/* #the-identity-model */}

Every persisted flow carries four identity columns, configured under `riptide.identity.*` (see the [ClickHouse reference](../reference/clickhouse.md#identity-columns)).

| ID | Nature | Isolation | Enforced by |
| --- | --- | --- | --- |
| **`tenant`** | ownership | hard (read and write) | server-validated: `CHECK tenant = getSetting('SQL_tenant')` against the ClickHouse user's `CONST` setting |
| **`organisation`** | ownership subdivision | hard | a second `CHECK` column; the isolation unit is `(tenant, organisation)`, so credentials are per `(tenant, org)` |
| **`zone`** | network placement | soft (filter) | payload column, unvalidated (the isolated network, or "network zone") |
| **`system`** | collector provenance | soft (filter) | payload column, unvalidated (per-instance identity) |

The hard/soft split is a placement decision.
Hard IDs are anchored to the authenticated ClickHouse credential: identity comes from authentication, never from a client claim, so a tampered collector config cannot cross-write.
Soft IDs are ordinary unvalidated payload used only for filtering.
All four are riptide-populated columns.

## The write barrier {/* #write-isolation-multi-tenant */}

In provisioned mode (`riptide.clickhouse.manage-schema=false`) an admin owns the schema and the RBAC, and each riptide process connects as a narrowly scoped writer that can only write its own tenant's rows.
The barrier is enforced entirely by ClickHouse.
Riptide never emits a `CHECK` constraint, because that would break single-tenant manage mode, which has no `SQL_tenant` setting.
It only stamps its configured `tenant` and `organisation` and inserts.

The mechanism is a per-row `CHECK` constraint that ties each row's `tenant` and `organisation` to a `CONST` custom setting pinned on the writer credential.
A collector whose config is tampered to claim another tenant still carries its own credential, so the server rejects the mismatched row.

The `flows` table is created by a riptide manage-mode start, by `onboard --create-schema`, or by equivalent admin DDL.
The constraints are added by `ALTER` and evaluated only on `INSERT`, so no `SQL_tenant` need be defined at DDL time:

```sql
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS org_pinned    CHECK organisation = getSetting('SQL_org');
```

Custom settings with the `SQL_` prefix must be enabled in the server configuration; the snippet is under [Server requirement](../reference/clickhouse.md#server-requirement) in the ClickHouse reference.

### What the barrier guarantees

Measured on ClickHouse 26.7.13.12 with a writer provisioned by `onboard`:

| Guarantee | What happens | Server answer |
| --- | --- | --- |
| An honest write persists | riptide's stamped `tenant` and `organisation` match the credential's `CONST` settings | the row is stored |
| A cross-tenant write is rejected | a tampered config stamps another tenant | `Code: 469 … Constraint 'tenant_pinned' for table riptide.flows … is violated at row 1 … (VIOLATED_CONSTRAINT)` |
| The pin cannot be lifted | a `SET` or a query-level `SETTINGS SQL_tenant=…` | `Code: 452 … Setting SQL_tenant should not be changed. (SETTING_CONSTRAINT_VIOLATION)` |
| Reads stay isolated | the row policy limits each reader to its own tenant's rows | only that tenant's rows come back |

The dead-letter table deliberately carries no `CHECK`.
Its job is to accept the rows `flows` refused, and the commonest refusal is that constraint firing.
So a writer whose config lies about its tenant has its write refused, and the dead letter it files is visible to the tenant it named.
The write is still refused; what changes is that the attempt leaves evidence.

## What the reader guarantees

The hardened BI credential is a real boundary, not a filter.
`TenantQueryIsolationIT` and, through the subcommand, `TenantOnboardingIT` prove each of these.

- Reads stay in-tenant.
  The row policy limits `bi_acme@riptide` to `tenant = 'acme'` rows, even against a shared table holding every tenant.
  The rollups carry the same policy, so a pre-aggregated query is bounded exactly as the raw one is; a rollup is not a way around the boundary, and neither is `flows_dead_letter` (`TenantOnboardingIT.deadLettersAreIsolatedByTenantLikeFlows`).
  The dead-letter table is guarded twice over: its `SELECT` is granted per user alongside the policy, so a tenant onboarded before the table existed cannot read it at all until it is re-onboarded (`aTenantOnboardedBeforeTheDeadLetterTableExistedIsRefusedOnIt`), rather than reading everything through a role grant no policy of its own covers.
- Reads stay in-database, and this half is the grant, not the policy.
  The reader holds `flow_reader@<database>`, which carries `SELECT` on that database's tables and no other's, so a query against another database is refused with `ACCESS_DENIED`.
  A policy could not have done this: the other database has no policy naming this reader, and an unnamed user reads everything (see [Row policies are not deny-by-default](#dropping-the-roles)).
  Before the roles were qualified, a `bi_*` account held `SELECT` on every provisioned database and read in full any of them where its tenant had never been onboarded.
- The reader cannot write.
  The `flow_reader@riptide` role grants no `INSERT` and pins `readonly`, so a write is rejected: `Code: 497 … Not enough privileges. To execute this query, it's necessary to have the grant INSERT(…) ON riptide.flows. (ACCESS_DENIED)`.
- The reader cannot change the schema.
  `allow_ddl = 0` rejects any DDL, so a compromised dashboard credential cannot alter or drop the table: `Code: 497 … it's necessary to have the grant DROP TABLE ON riptide.flows. (ACCESS_DENIED)`.
- Grafana's query builder still works.
  The `system.databases`, `system.tables` and `system.columns` grants let it introspect the schema.

`readonly = 2` rather than `1` blocks writes and DDL while tolerating the read-only settings an HTTP client sends per query; `readonly = 1` would reject those and break the connection.

## Why the recipe is role-based

The schema, the grants, the reader hardening, the CHECK barrier and the quota are one-time per-database objects.
Per tenant, what remains is two scoped users, two role grants and one row policy per table (`flows`, `flows_dead_letter` and each rollup, all sharing the tenant-literal predicate), plus, for `flows_dead_letter` alone, the `SELECT` itself, granted per user rather than to the shared reader role.

The writer is named on the `flows` policy alongside the reader to constrain it, not to enable it.
Its predicate is the same tenant the CHECK barrier already pins, so it grants no extra row and removes every foreign one.
The rollup policies name the reader only: the writer holds no `SELECT` on a rollup target at all, so being unnamed there exposes nothing, and it reaches a rollup by `INSERT` through the materialized view, which no row policy filters.

The writer also holds `SELECT` on `flows`, because a materialized view runs as the inserting user and pushing a row into a rollup requires `SELECT` on the view's source table.
It holds `SHOW TABLES` on each rollup's `_mv`, and not `SELECT`: a row policy attached to a rollup target does not apply when the same rows are read through the view's name, so `SELECT` there would hand every writer of the database a read path around the policy.
`SHOW TABLES` makes the view visible for the startup shape check and grants no data access.

The dead-letter `SELECT` is per user because the table's row policies arrive one `onboard` run at a time while a role grant would arrive for every tenant at once.
Re-onboarding a single tenant of a multi-tenant database would otherwise let every other tenant read its dead letters.
Per user, the grant and the policy cannot separate.

## Why object names carry their database {/* #object-names-carry-their-database */}

ClickHouse users, roles and quotas live in one flat namespace across the whole server; a database is not a scope for them.
So every one of those names is composed as `<name>@<database>`, and it is the database that makes the tenant's account unique on the instance.

This matters as soon as one server holds two riptide databases.
Without the qualifier, onboarding tenant `acme` into a second database rewrote the first `writer_acme`'s password, and one shared `flow_writer` role gave every writer `INSERT` on every provisioned database's `flows` and rollups.
With it, the second onboarding creates a separate account, and a cross-database write is refused with `ACCESS_DENIED` because the grant is not there.
The `CHECK` barrier cannot substitute for this: `tenant_pinned` passes whenever both databases carry the same tenant id.

`@` is the delimiter because tenant and database names are both restricted to `[A-Za-z0-9_-]+`, which cannot produce it.
An underscore would be ambiguous: tenant `foo` in database `bar_baz` and tenant `foo_bar` in database `baz` would both spell `writer_foo_bar_baz`, and the second onboarding would take over the first's account.
Names containing `@` must be backticked in SQL, and they authenticate normally over both HTTP basic auth and the native protocol.

Riptide's own `riptide.clickhouse.username` is a plain field and takes the name verbatim.
Anywhere the username is embedded inside a URL (a JDBC URL's userinfo, `http://user:pass@host:8123/`, Grafana's ClickHouse datasource when configured by URL, `clickhouse-client --url`) the `@` terminates the userinfo component and the connection fails with an opaque host-resolution or authentication error.
Write it as `%40` there:

```text
http://writer_acme%40riptide:secret@clickhouse:8123/
jdbc:clickhouse://clickhouse:8123/riptide?user=writer_acme%40riptide
```

`clickhouse-client --user 'writer_acme@riptide'` and Grafana's separate *Username* field need no encoding, because neither parses the value as part of a URL.

Row policies are the exception, and deliberately so.
A policy's identity is already `name ON db.table`, so `acme_iso ON db_a.flows` and `acme_iso ON db_b.flows` are two distinct objects.
Their names are left alone.

## Row policies are not deny-by-default {/* #dropping-the-roles */}

ClickHouse ships `users_without_row_policies_can_read_rows` set to `true` in its default `config.xml`, and has done since the setting was introduced; grep that name rather than a line number, which moves between releases.
A user holding `SELECT` on a table and named by no policy on that table therefore reads every row, not none.
Measured on the pinned image, not inferred: with `acme` and `other` rows present, a granted user named by the policy returned only `acme`; a granted user named by no policy returned both.

Two consequences run through the provisioning recipe.
Naming a principal on a policy restricts it; it never grants access the principal would otherwise lack.
Removing a principal from a policy while it still holds `SELECT` widens it to every tenant, which is why `onboard` keeps a live pre-rename account on the policies it rewrites (see [Migrate a deployment onboarded before the rename](../guides/migrate-tenant-accounts.md)).

If you set the server setting to `false`, the recipe still works; the failure mode flips from "reads too much" to "reads nothing".

The policies are re-issued with `CREATE ROW POLICY OR REPLACE` rather than `IF NOT EXISTS`, for the same reason `onboard` re-issues `ALTER USER` for passwords: a policy left over from an earlier run keeps its old `TO` list, so a re-run would not pick up a changed grantee.
`OR REPLACE` makes the policy match the recipe every time.
A policy `TO` list widened by hand is therefore reverted on the next run, so route extra grantees through provisioning, not manual DDL.

A single shared row policy scoped by `getSetting('SQL_tenant')` does not work: ClickHouse raises `UNKNOWN_SETTING` whenever a principal without that setting evaluates it.
The row policy must stay a per-tenant literal.

## Grafana topology

The isolation boundary in Grafana OSS is one Grafana org, or one Grafana instance, per tenant, each with a datasource that authenticates as that tenant's `bi_<tenant>@<database>` user.
The ClickHouse row policy does the enforcing; Grafana holds the right credential.

What is not a boundary on OSS:

- Per-tenant datasources inside one shared org.
  Any user in that org can query any datasource, so this leaks across tenants.
  Datasource-level permissions are a Grafana Enterprise feature.
- Dashboard-variable tenant filtering (a `$tenant` template variable).
  A viewer can edit the variable to any value.
  Use it for UX within a tenant, never for isolation.

The guarantee comes from the ClickHouse credential plus the row policy, so it holds regardless of what a dashboard sends.

## Trade-offs

| Choice | Gained | Given up |
| --- | --- | --- |
| Identity from the credential's `CONST` settings, not from the row | a tampered config cannot cross-write | the `SQL_` prefix must be enabled server-side, and manage mode cannot use the barrier |
| One role per database, users qualified by database | a cross-database write is `ACCESS_DENIED`, not a predicate that happens to match | `@` in every account name, which URLs must percent-encode |
| Per-tenant literal row policies | isolation that holds for readers and rollups alike | one policy per table per tenant, re-asserted on every `onboard` |
| Dead-letter table without a `CHECK` | refused batches are kept as evidence | a lying writer's dead letter is visible to the tenant it named |

## Open questions

- Does re-running `onboard` against a database another admin provisioned need `ROLE ADMIN`? The requirement in the [admin privileges table](../reference/provisioning-cli.md#admin-privileges) is stated from ClickHouse's grant rules, not from a measurement or a test in this repository. `(unverified)`
- Was the pre-rename quota named `flow_ingest`? The only quota name the code composes is `onboard`'s qualified `flow_ingest@<database>`; `revoke-legacy` touches no quota, and the unqualified name in the drop statement on the migration page is inferred. `(unverified)`
- How many tenants does the per-user model carry? "Low hundreds" is an estimate with no measurement behind it. A pivot to a shared BI user keyed by `quota_key`, with tenant scoping at the query layer, has been discussed but has no issue yet; the identity columns and row-policy predicates would carry over unchanged. `(unverified)`
- The "not deny-by-default" measurement above was made during development on the pinned image and is not repeated by an integration test that asserts it. The provisioning tests pin the consequence (an unnamed writer is constrained) rather than the setting.
