---
sidebar_position: 6
title: Multi-tenancy
---

# Multi-tenant provisioning

Run many riptide processes — one per isolated network, several per network — writing into **one
ClickHouse cluster**, with **hard isolation** between tenants and organisations and **soft
filtering** by zone and system. A monitoring provider can collect NetFlow/IPFIX/sFlow from many
customers' isolated networks (overlapping RFC 1918 space included) and keep each customer's data
provably separate, on both the write and the read side.

This page is the operator runbook. The write barrier's mechanism is explained on the
[ClickHouse](../configuration/clickhouse.md#write-isolation-multi-tenant) page; here we give the
end-to-end onboarding recipe, the read-side hardening, topology guidance, and the scaling ceiling.

## The identity model

Every persisted flow carries four identity columns (see
[Identity columns](../configuration/clickhouse.md#identity-columns)):

| ID | Nature | Isolation | Enforced by |
|---|---|---|---|
| `tenant` | ownership | **hard** (read + write) | server-validated: `CHECK tenant = getSetting('SQL_tenant')` vs the CH user's `CONST` setting |
| `organisation` | ownership subdivision | **hard** | second `CHECK` column — the isolation unit is `(tenant, organisation)`, so credentials are per-`(tenant, org)` |
| `zone` | network placement | soft (filter) | payload column, unvalidated (the isolated network / "network zone") |
| `system` | collector provenance | soft (filter) | payload column, unvalidated (per-instance identity) |

The **hard/soft split is a placement decision**: hard IDs are anchored to the authenticated
ClickHouse credential — identity comes from *authentication, never from a client claim* — so a
tampered collector config cannot cross-write. Soft IDs are ordinary unvalidated payload used only
for filtering. All four are riptide-populated columns.

## Prerequisites

- ClickHouse with **replicated access storage** so users, roles, and row policies exist on every
  node — otherwise a credential provisioned on one node is unknown on another. The default config
  stores SQL-created users in a node-local `local_directory`, so the snippet must **replace** the
  whole `user_directories` block (not merge into it) — keep `users_xml` for the bootstrap admin,
  drop `local_directory`, and add `replicated` so new users land in Keeper:

  ```xml
  <!-- /etc/clickhouse-server/config.d/access-storage.xml -->
  <clickhouse>
      <user_directories replace="replace">
          <users_xml>
              <path>users.xml</path>
          </users_xml>
          <replicated>
              <zookeeper_path>/clickhouse/access/</zookeeper_path>
          </replicated>
      </user_directories>
  </clickhouse>
  ```

  Without `replace="replace"` the snippet appends to the default block, `local_directory` stays,
  and SQL-created users are written there — node-local, exactly the failure this prevents.

- The `SQL_` custom-settings prefix enabled (write-barrier requirement) — see the
  [server requirement](../configuration/clickhouse.md#server-requirement).
- Riptide running in [validate mode](../configuration/clickhouse.md#schema-ownership)
  (`manage-schema=false`). On a fresh **single-node** server, `onboard --create-schema` bootstraps
  the database and `flows` table itself (see [What it provisions](#what-it-provisions)) — no manual
  DDL. On a **replicated cluster**, pre-create the `flows` table admin-side (e.g.
  `ReplicatedMergeTree`, `ON CLUSTER`) and run `onboard` *without* `--create-schema`: the bootstrap
  DDL is single-node (`MergeTree()`, no `ON CLUSTER`) and would create a node-local table on
  whichever replica the admin client hits, while the roles and grants replicate.

## Onboard a tenant

Use the `onboard` subcommand — it runs the whole recipe idempotently with one substituted
`(tenant, org)` and prints the config stanza for the tenant's collector. It runs with **admin**
credentials passed at invocation (never the collector's scoped credential) and, because it needs no
Spring context, the running collector never contains any provisioning code.

```bash
java -jar riptide.jar onboard \
  --admin-url https://clickhouse:8443 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --tenant acme --org acme-eu \
  --writer-secret env://ACME_WRITER_PW --reader-secret env://ACME_READER_PW
```

```
# printed to stdout — paste into the tenant's collector config:
riptide.clickhouse.username=writer_acme@riptide
riptide.clickhouse.password=env://ACME_WRITER_PW
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
```

Secret references resolve through the built-in resolvers (`plain`, `env://`, `file://`);
`--writer-secret`/`--reader-secret` are the passwords for the tenant's writer and BI users. Add
`riptide.clickhouse.manage-schema=false` and `riptide.identity.zone` to the collector config as
needed.

On a fresh single-node server, add **`--create-schema`** to the first onboard: it creates the
database and `flows` table (with `--ttl-days N` retention, default 30, max 10950 — ClickHouse's
`DateTime` ends in 2106) before the grants and constraints that need them. `--ttl-days` applies
only to a table this run creates: it requires `--create-schema`, and a re-run against an existing
table warns that retention is unchanged. Without the flag, a missing database or table **fails before any
statement runs** — so a typo'd `--database` can never silently provision a phantom database — and
the run sends no `CREATE` statement at all, which keeps a least-privilege admin working (ClickHouse
checks `CREATE` privileges even when `IF NOT EXISTS` would no-op).

### Admin privileges

| mode | minimum privileges for the admin credential |
|---|---|
| default (schema exists) | `CREATE USER`/`CREATE ROLE`/`CREATE QUOTA`/`CREATE ROW POLICY`, `ALTER USER`/`ALTER ROLE`, `DROP USER`/`DROP ROW POLICY` (offboard), `ALTER TABLE` on `<db>.flows`, and `INSERT`, `SELECT` on `<db>.flows` plus `SELECT` on `system.databases/tables/columns` **with grant option** (they are granted onward to the roles) |
| `--create-schema` | the above, plus `CREATE DATABASE ON <db>.*`, `CREATE TABLE ON <db>.*` (the `flows` table and the rollup targets) and `CREATE VIEW ON <db>.*` (the rollups' materialized views) |

`onboard` is safe to re-run: it reconciles the writer/reader **passwords** to the current secret, so
rotating a secret and re-running updates ClickHouse (the users' `CONST` settings are preserved; the
row policies are **re-asserted** to match the recipe — a policy `TO` list widened by hand is
reverted on the next run, so route extra grantees through provisioning, not manual DDL). To remove a tenant: `offboard --admin-url … --tenant acme --yes` (drops its users — the `@<database>` accounts **and** the pre-rename unqualified ones — and its row policy from `flows` **and every rollup**; the database's roles/constraints/quota stay, since other tenants hold them).

### Adding rollups to an existing deployment

A database provisioned before the rollups existed has a perfectly good `flows` table, so the
schema check passes while the rollups are simply absent. `onboard` checks for them separately and
refuses to run without `--create-schema`:

```
database 'riptide' is missing the 1-minute rollup tables or their materialized views — re-run
with --create-schema to add them. …
```

Re-running with `--create-schema` adds them in place. This creates tables and materialized views
only — **the `flows` table and its data are untouched**. The check covers each rollup's target
*and* its view, so an interrupted bootstrap that left targets without views is detected rather
than reading as healthy (which would leave the rollups silently empty).

Because a materialized view does not backfill, rollups added this way cover traffic from creation
onward. See [Rollups](../configuration/clickhouse.md#rollups) for the table layout and how to
backfill if you need the history.

**After an upgrade that adds a rollup dimension, re-running `onboard` is required, not optional.** A validate-mode collector issues no DDL, so until it is run riptide declines all four rollups and answers every long-range query from raw `flows` — correct, but truncated at the raw retention window.

Re-running `onboard` brings existing rollups up to the running version's shape, appending any dimension a release has added. It is idempotent and safe to re-run: the statements no-op once a rollup is current, and no aggregation is interrupted. This is the only path a provisioned deployment has, since its collector runs in validate mode and issues no DDL.

**Then restart the collector.** Which rollups are usable is decided once, at startup — a schema does not change under a running collector — so one that declined them at boot keeps answering from raw `flows` until it restarts, no matter how complete the repair was. Without the restart the operator sees a successful `onboard` and no change in behaviour.

If `onboard` reports a rollup as *left as it is*, that rollup is deliberately not repaired and gets no materialized view. The rest of the run proceeds normally — roles, users and password rotation are unaffected — and the named rollup stays out of the query path until the state its message describes is fixed.

`onboard` reads each rollup's live sorting key first and applies the same rule the collector does, so a change that would *shrink* a key is refused rather than applied. That guard is not optional: ClickHouse itself accepts such a shrink on an upgraded table, because the primary key was frozen at the narrower shape, so nothing below riptide would stop a rollup's grain changing in place.

### What it provisions

The recipe is **role-based**: the schema, the grants, the reader hardening, the CHECK barrier, and
the quota are one-time **per-database** objects, so per-tenant reduces to the scoped users + role
grants + one row policy per table (`flows` and each rollup, all sharing the tenant-literal
predicate). `onboard` ensures the per-database objects on first run and adds the per-tenant part:

```sql
-- Only with --create-schema, and only when the schema is actually missing (a default run emits
-- no CREATE statement, so it needs no CREATE privileges). IF NOT EXISTS never replaces a table.
CREATE DATABASE IF NOT EXISTS riptide;
CREATE TABLE IF NOT EXISTS riptide.flows (…);  -- single-node MergeTree, TTL from --ttl-days (default 30)
-- The 1-minute rollups: targets first, then the materialized views that feed them (a view cannot
-- be created before its TO table). TTL is 365 days — the aggregates outlive the raw rows.
CREATE TABLE IF NOT EXISTS riptide.flows_by_application_1m (…);          -- and three more
CREATE MATERIALIZED VIEW IF NOT EXISTS riptide.flows_by_application_1m_mv
  TO riptide.flows_by_application_1m AS SELECT … FROM riptide.flows AS f GROUP BY …;
-- Once per database (idempotent): roles carry every per-tenant grant and the reader hardening.
-- Users, roles and quotas are instance-wide objects, so their names carry the database.
CREATE ROLE IF NOT EXISTS `flow_writer@riptide`;
GRANT INSERT ON riptide.flows TO `flow_writer@riptide`;
-- The writer also reads flows: a materialized view runs as the inserting user, so pushing a row
-- into a rollup requires SELECT on the view's source table.
GRANT SELECT ON riptide.flows TO `flow_writer@riptide`;
CREATE ROLE IF NOT EXISTS `flow_reader@riptide`;
GRANT SELECT ON riptide.flows TO `flow_reader@riptide`;
GRANT SELECT ON system.databases TO `flow_reader@riptide`;
GRANT SELECT ON system.tables    TO `flow_reader@riptide`;
GRANT SELECT ON system.columns   TO `flow_reader@riptide`;   -- the catalog a query builder needs
-- readonly = 2 blocks writes and DDL while tolerating the read-only settings an HTTP client sends
-- per query; readonly = 1 would reject those and break the connection.
ALTER ROLE `flow_reader@riptide` SETTINGS readonly = 2, allow_ddl = 0;
-- The write barrier: each row's tenant/org must equal the writer credential's pinned CONST setting.
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS org_pinned    CHECK organisation = getSetting('SQL_org');
-- One quota keyed by user gives every writer its own bucket (written_bytes — written_rows is not a metric).
CREATE QUOTA IF NOT EXISTS `flow_ingest@riptide` FOR INTERVAL 1 hour MAX written_bytes = 50000000000
  KEYED BY user_name TO `flow_writer@riptide`;
-- Every rollup gets the same treatment as flows, for both roles.
GRANT INSERT ON riptide.flows_by_application_1m TO `flow_writer@riptide`;   -- and the other three
GRANT SELECT ON riptide.flows_by_application_1m TO `flow_reader@riptide`;

-- Per tenant (the residual): two scoped users + role grants + one row policy per table.
CREATE USER IF NOT EXISTS `writer_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT `flow_writer@riptide` TO `writer_acme@riptide`;
CREATE USER IF NOT EXISTS `bi_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT `flow_reader@riptide` TO `bi_acme@riptide`;
-- The writer is named on the flows policy alongside the reader: a row policy is deny-by-default
-- for anyone it does not name, and the writer must read flows for the rollup views to push. Its
-- predicate is the same tenant the CHECK barrier already pins, so it grants no extra row.
-- The policy name is NOT qualified: a policy's identity is `name ON db.table`, so it is already
-- scoped by the table it hangs on.
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`, `writer_acme@riptide`;
-- The rollup policies name the reader only — the writer reaches a rollup by INSERT through its
-- materialized view, which no row policy filters.
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows_by_application_1m
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`;                    -- and the other three
```

### Object names carry their database

ClickHouse users, roles and quotas live in one flat namespace across the whole server — a database is not a scope for them.
So every one of those names is composed as `<name>@<database>`, and it is the database that makes the tenant's account unique on the instance.

This matters as soon as one server holds two riptide databases.
Without the qualifier, onboarding tenant `acme` into a second database rewrote the first `writer_acme`'s password, and one shared `flow_writer` role gave every writer `INSERT` on every provisioned database's `flows` and rollups.
With it, the second onboarding creates a separate account, and a cross-database write is refused with `ACCESS_DENIED` because the grant simply is not there.
The `CHECK` barrier cannot substitute for this: `tenant_pinned` passes whenever both databases carry the same tenant id.

`@` is the delimiter because tenant and database names are both restricted to `[A-Za-z0-9_-]+`, which cannot produce it.
An underscore would be ambiguous — tenant `foo` in database `bar_baz` and tenant `foo_bar` in database `baz` would both spell `writer_foo_bar_baz`, and the second onboarding would take over the first's account.
Names containing `@` must be backticked in SQL, and they authenticate normally over both HTTP basic auth and the native protocol.

Row policies are the exception, and deliberately so.
A policy's identity is already `name ON db.table`, so `acme_iso ON db_a.flows` and `acme_iso ON db_b.flows` are two distinct objects.
Their names are left alone.

### Upgrading a deployment onboarded before the rename

Nothing breaks on upgrade, and nothing is removed for you.
An instance provisioned under the old naming keeps its unqualified `writer_<tenant>` / `bi_<tenant>` users and its `flow_writer` / `flow_reader` roles, and its collector keeps working.

Re-running `onboard` **adds** the qualified account alongside the old one — it does not rename or drop it, because the tenant's collector is still authenticating as the old one until you paste the new stanza.
The run prints a warning naming the leftover account and the `DROP USER` to run:

```
warning: the pre-rename account 'writer_acme' still exists on this server. It holds the old
instance-wide role, so it can still reach every database provisioned before the rename. Once the
tenant's collector (and any BI datasource) has moved to the '@riptide' account below, drop it:
DROP USER `writer_acme`
```

The order that avoids downtime, per tenant:

1. Re-run `onboard` for the tenant. The qualified account is created with the same secret.
2. Update the collector config to the username from the new stanza, and any Grafana/MCP datasource to the new `bi_<tenant>@<database>`. Restart them.
3. Drop the old account: ``DROP USER `writer_acme`;`` and ``DROP USER `bi_acme`;``.

Until step 3 the old account still holds the old instance-wide role, so it can still write to every database provisioned before the rename — that is the whole reason to finish.

`offboard` needs no such sequencing: it drops both namings, so it revokes a tenant whether or not that tenant has been migrated.

The orphaned `flow_writer` / `flow_reader` roles are **not** dropped by `offboard`, because they are instance-wide and may still be granted to a tenant you have not migrated.
Once no user holds them (`SELECT * FROM system.role_grants WHERE granted_role_name IN ('flow_writer', 'flow_reader')` returns nothing), drop them by hand.

:::note[Why `OR REPLACE` rather than `IF NOT EXISTS`]

For the same reason `onboard` re-issues `ALTER USER` for passwords: a policy left over from an
earlier run keeps its old `TO` list, so a re-run would not pick up a changed grantee. `OR REPLACE`
makes the policy match the recipe every time.

:::

:::note

A single shared row policy scoped by `getSetting('SQL_tenant')` does **not** work — ClickHouse
raises `UNKNOWN_SETTING` whenever a principal without that setting evaluates it. The row policy must
stay a per-tenant literal.

:::

### What the reader guarantees

The hardened BI credential is a real boundary, not just a filter (proven by `TenantQueryIsolationIT`
and, through the subcommand, `TenantOnboardingIT`):

- **Reads stay in-tenant** — the row policy limits `bi_acme@riptide` to `tenant = 'acme'` rows, even
  against a shared table holding every tenant. The rollups carry the same policy, so a
  pre-aggregated query is bounded exactly as the raw one is — a rollup is not a way around the
  boundary.
- **Cannot write** — the `flow_reader@riptide` role grants no `INSERT` and pins `readonly`, so a
  write is rejected (`ACCESS_DENIED`).
- **Cannot change schema** — `allow_ddl = 0` rejects any DDL, so a compromised dashboard credential
  cannot alter or drop the table.
- **Query builder still works** — the `system.databases/tables/columns` grants let Grafana's query
  builder introspect the schema.

## Grafana topology

The isolation boundary in Grafana **OSS** is **one Grafana org (or one Grafana instance) per
tenant**, each with a datasource that authenticates as that tenant's `bi_<tenant>@<database>` user. The ClickHouse
row policy does the enforcing; Grafana just holds the right credential.

What is **not** a boundary on OSS:

- **Per-tenant datasources inside one shared org** — any user in that org can query any datasource,
  so this leaks across tenants. Datasource-level permissions are a Grafana **Enterprise** feature.
- **Dashboard-variable tenant filtering** (a `$tenant` template variable) — never a boundary at
  all: a viewer can edit the variable to any value. Use it for UX within a tenant, never for
  isolation.

The guarantee comes from the ClickHouse credential + row policy, so it holds regardless of what a
dashboard sends.

## Scaling ceiling

Per-tenant ClickHouse users and row policies work comfortably into the **low hundreds of tenants**.
Beyond that, the per-user access objects become the bottleneck and the model pivots to a **shared
BI user keyed by `quota_key`** with tenant scoping applied at the application/query layer rather
than one CH user per tenant.

This is a known future migration, **out of scope** for the current release — documented here so it
is a planned step, not a surprise. Nothing in the per-tenant model above blocks it: the identity
columns and row-policy predicates carry over unchanged.
