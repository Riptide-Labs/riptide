---
sidebar_position: 4
title: ClickHouse
---

# ClickHouse

Enriched flows are persisted to ClickHouse:

```properties
riptide.clickhouse.endpoint=http://localhost:8123
riptide.clickhouse.username=default
#riptide.clickhouse.password=vault://secret/riptide/clickhouse#password
riptide.clickhouse.database=riptide
riptide.clickhouse.manage-schema=true
```

## Credentials

`riptide.clickhouse.username` and `riptide.clickhouse.password` are **secret references**,
resolved through the same SPI as SNMP credentials:

- a bare literal is used verbatim (plain fallback — existing literal configs keep working);
- a `scheme://…` reference is resolved from a secret store at startup: `env://VAR`,
  `file:///path` (optionally `#key` into a properties file), `vault://…`, `sops://…`.

Resolution happens once, when the ClickHouse client is built. An **unresolvable reference fails
startup** — a database credential that cannot resolve is fatal (unlike an SNMP one, which
degrades). Leave `password` unset for the default user's empty password; a blank value is not a
valid reference. Per-tenant writer credentials are sourced this way so no plaintext appears in
configuration:

```properties
riptide.clickhouse.username=writer_acme
riptide.clickhouse.password=vault://secret/riptide/clickhouse/acme#password
```

## Schema ownership

`riptide.clickhouse.manage-schema` (boolean, default `true`) selects who owns the schema:

- **`true` (default, single-tenant)** — riptide ensures the schema idempotently at startup:
  the database with `CREATE DATABASE IF NOT EXISTS` (so a fresh single-node install needs no
  manual DDL — the configured user needs `CREATE` rights), the `flows` table with
  `CREATE TABLE IF NOT EXISTS` (an existing table is not replaced, so its data survives), and
  the `samples` view with `CREATE OR REPLACE VIEW` (a view holds no data, so it is always
  refreshed and can never go stale). A fresh install is created; a restart keeps the data — so
  **flow data now survives a Riptide restart**.
- **`false` (provisioned / multi-tenant)** — the collector creates nothing. It validates that the
  `flows` table exists and carries every column it inserts and **fails startup with a clear,
  provisioning-pointing error** if it does not. Use this when an admin owns the schema and
  RBAC and each riptide process is a narrowly-scoped writer that only uses the table. The admin-side
  [`onboard` subcommand](../deploy/multi-tenancy.md#what-it-provisions) creates the database and
  `flows` table as part of provisioning, so this mode needs no separate manual schema step.

In both modes, startup verifies the `flows` table is present and carries every column riptide
inserts (including the `tenant`/`organisation`/`zone`/`system` identity columns) by reading the
table's own schema — so the check works even for a narrowly-granted writer without server-catalog
access, and a stale or mis-provisioned schema fails fast rather than surfacing later as an opaque
insert error.

:::warning

Schema evolution is not migrated automatically. Because manage mode uses
`CREATE TABLE IF NOT EXISTS`, a schema change between Riptide versions is **not** applied to
an existing table — the startup column check fails fast and the operator must drop and let
Riptide recreate the table (or re-provision it) until schema migrations land.

:::

Enrichment results are denormalized into the flow row at write time — exporter address,
resolved interface data (`inputSnmpIfName`/`ifAlias`/`ifSpeed` and the `output…`
counterparts), hostnames, classification, and locality — so queries never need join-time
lookups.

## Identity columns

Every persisted flow carries four identity columns stamped by the collecting process:
`tenant`, `organisation`, `zone` (the isolated network) and `system` (per-instance
provenance). They default so an out-of-the-box single-tenant deployment works unchanged —
`tenant`, `organisation` and `zone` default to `default`; `system` defaults to the process
host name (`riptide.identity.system` → `HOSTNAME` → `InetAddress.getHostName()` →
`default`). Configure them under `riptide.identity`:

```properties
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
riptide.identity.zone=dmz
riptide.identity.system=collector-01
```

The flows table sorts by `(tenant, organisation, toStartOfHour(timestamp), <flow tuple>)`;
`zone` and `system` are filter dimensions and stay out of the sort key. Partitioning stays
time-only (`toYYYYMMDD(timestamp)`). For the full identity model and how `tenant`/`organisation`
anchor hard isolation, see the [Multi-tenancy runbook](../deploy/multi-tenancy.md#the-identity-model).

:::note

`zone` replaces the former `riptide.location` key. `riptide.location` is deprecated but
still accepted for one release (mapped to `zone` with a warning); prefer
`riptide.identity.zone`.

:::

## Write isolation (multi-tenant)

In provisioned mode (`manage-schema=false`) an admin owns the schema and RBAC, and each riptide
process connects as a **narrowly-scoped writer** that can only write its own tenant's rows. The
barrier is enforced entirely by ClickHouse — **riptide never emits a `CHECK` constraint** (that
would break single-tenant manage mode, which has no `SQL_tenant` setting); it only stamps its
configured `tenant`/`organisation` (see [Identity columns](#identity-columns)) and inserts.

The mechanism is a per-row `CHECK` constraint that ties each row's `tenant`/`organisation` to a
`CONST` custom setting pinned on the writer credential. A collector whose config is tampered to
claim another tenant still carries its own credential, so the server rejects the mismatched row.

### Server requirement

Custom settings with the `SQL_` prefix must be enabled. This is **server config, not an env
var** — add a `config.d` snippet:

```xml
<!-- /etc/clickhouse-server/config.d/custom-settings.xml -->
<clickhouse>
    <custom_settings_prefixes>SQL_</custom_settings_prefixes>
</clickhouse>
```

### Provisioning

The `flows` table is created by a riptide manage-mode start (or equivalent DDL); the barrier
constraints are added by `ALTER` (evaluated only on `INSERT`, so no `SQL_tenant` need be defined at
DDL time):

```sql
ALTER TABLE riptide.flows ADD CONSTRAINT tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT org_pinned    CHECK organisation = getSetting('SQL_org');
```

The per-tenant writer/reader users, role grants, quota, and row policy are provisioned by the
[`onboard` subcommand](../deploy/multi-tenancy.md#onboard-a-tenant) — one command per
`(tenant, org)` that also prints the collector's config stanza. See the
[Multi-tenancy runbook](../deploy/multi-tenancy.md) for the full recipe.

### What the barrier guarantees

- **Honest write persists** — riptide's stamped `tenant`/`organisation` match the credential's
  `CONST` settings, so rows are stored.
- **Cross-tenant write is rejected** — a tampered config stamping another tenant fails the
  constraint: `Code: 469 … (VIOLATED_CONSTRAINT)`.
- **The pin cannot be lifted** — any attempt to override the `CONST` setting (a `SET` or a
  query-level `SETTINGS SQL_tenant=…`) fails: `Code: 452 … (SETTING_CONSTRAINT_VIOLATION)`.
- **Reads stay isolated** — the row policy limits each reader to its own tenant's rows.

:::note

This section covers the **write** provisioning. For the read-side hardening (per-tenant BI
users + row policies), Grafana topology, the end-to-end onboarding recipe, and the scaling
ceiling, see the [Multi-tenancy runbook](../deploy/multi-tenancy.md).

:::
