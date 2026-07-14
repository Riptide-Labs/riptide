---
sidebar_position: 4
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
- The `flows` table provisioned by an admin, with riptide running in
  [validate mode](../configuration/clickhouse.md#schema-ownership) (`manage-schema=false`).

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
riptide.clickhouse.username=writer_acme
riptide.clickhouse.password=env://ACME_WRITER_PW
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
```

Secret references resolve through the built-in resolvers (`plain`, `env://`, `file://`);
`--writer-secret`/`--reader-secret` are the passwords for the tenant's writer and BI users. Add
`riptide.clickhouse.manage-schema=false` and `riptide.identity.zone` to the collector config as
needed.

`onboard` is safe to re-run: it reconciles the writer/reader **passwords** to the current secret, so
rotating a secret and re-running updates ClickHouse (the users' `CONST` settings and row policy are
preserved). To remove a tenant: `offboard --admin-url … --tenant acme --yes` (drops its users and
row policy; the shared roles/constraints/quota stay).

### What it provisions

The recipe is **role-based**: the grants, the reader hardening, the CHECK barrier, and the quota are
one-time objects, so per-tenant reduces to the scoped users + role grants + one literal row policy.
`onboard` ensures the one-time objects on first run and adds the per-tenant part:

```sql
-- Once per cluster (idempotent): roles carry every per-tenant grant and the reader hardening.
CREATE ROLE IF NOT EXISTS flow_writer;
GRANT INSERT ON riptide.flows TO flow_writer;
CREATE ROLE IF NOT EXISTS flow_reader;
GRANT SELECT ON riptide.flows TO flow_reader;
GRANT SELECT ON system.databases TO flow_reader;
GRANT SELECT ON system.tables    TO flow_reader;
GRANT SELECT ON system.columns   TO flow_reader;   -- the catalog a query builder needs
-- readonly = 2 blocks writes and DDL while tolerating the read-only settings an HTTP client sends
-- per query; readonly = 1 would reject those and break the connection.
ALTER ROLE flow_reader SETTINGS readonly = 2, allow_ddl = 0;
-- The write barrier: each row's tenant/org must equal the writer credential's pinned CONST setting.
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS org_pinned    CHECK organisation = getSetting('SQL_org');
-- One quota keyed by user gives every writer its own bucket (written_bytes — written_rows is not a metric).
CREATE QUOTA IF NOT EXISTS flow_ingest FOR INTERVAL 1 hour MAX written_bytes = 50000000000
  KEYED BY user_name TO flow_writer;

-- Per tenant (the residual): two scoped users + role grants + one literal row policy.
CREATE USER IF NOT EXISTS writer_acme IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT flow_writer TO writer_acme;
CREATE USER IF NOT EXISTS bi_acme IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT flow_reader TO bi_acme;
CREATE ROW POLICY IF NOT EXISTS acme_iso ON riptide.flows FOR SELECT USING tenant = 'acme' TO bi_acme;
```

:::note

A single shared row policy scoped by `getSetting('SQL_tenant')` does **not** work — ClickHouse
raises `UNKNOWN_SETTING` whenever a principal without that setting evaluates it. The row policy must
stay a per-tenant literal.

:::

### What the reader guarantees

The hardened BI credential is a real boundary, not just a filter (proven by `TenantQueryIsolationIT`
and, through the subcommand, `TenantOnboardingIT`):

- **Reads stay in-tenant** — the row policy limits `bi_acme` to `tenant = 'acme'` rows, even
  against a shared table holding every tenant.
- **Cannot write** — the `flow_reader` role grants no `INSERT` and pins `readonly`, so a write is
  rejected (`ACCESS_DENIED`).
- **Cannot change schema** — `allow_ddl = 0` rejects any DDL, so a compromised dashboard credential
  cannot alter or drop the table.
- **Query builder still works** — the `system.databases/tables/columns` grants let Grafana's query
  builder introspect the schema.

## Grafana topology

The isolation boundary in Grafana **OSS** is **one Grafana org (or one Grafana instance) per
tenant**, each with a datasource that authenticates as that tenant's `bi_*` user. The ClickHouse
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
