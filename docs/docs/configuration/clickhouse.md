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
#riptide.clickhouse.async-inserts=   # unset: follows manage-schema — see below
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

The database name (`riptide.clickhouse.database`) must match `[A-Za-z0-9_-]+` — names with other
characters (dots, spaces, unicode) are rejected at startup in manage mode. This is stricter than
what ClickHouse accepts under backtick quoting; rename such a database or use validate mode.

- **`true` (default, single-tenant)** — riptide ensures the schema idempotently at startup:
  the database with `CREATE DATABASE IF NOT EXISTS` (so a fresh single-node install needs no
  manual DDL — the configured user needs `CREATE` rights), the `flows` table with
  `CREATE TABLE IF NOT EXISTS` (an existing table is not replaced, so its data survives), and
  the `samples` view with `CREATE OR REPLACE VIEW` (a view holds no data, so it is always
  refreshed and can never go stale), and the [1-minute rollups](#rollups) with
  `CREATE TABLE IF NOT EXISTS` / `CREATE MATERIALIZED VIEW IF NOT EXISTS`. A fresh install is
  created; a restart keeps the data — so **flow data now survives a Riptide restart**.

  :::warning[Hand-created `samples` views need re-creating]

  The `samples` bucket split was corrected in #270 (older definitions under-report traffic by up
  to the flow's bucket count). Manage-mode collectors heal on restart, but a `samples` view an
  admin created by hand — e.g. in a provisioned deployment — keeps the old formula until it is
  re-created from the current definition.

  :::
- **`false` (provisioned / multi-tenant)** — the collector creates nothing. It validates that the
  `flows` table exists and carries every column it inserts and **fails startup with a clear,
  provisioning-pointing error** if it does not. Use this when an admin owns the schema and
  RBAC and each riptide process is a narrowly-scoped writer that only uses the table. On a fresh
  single-node server the admin-side
  [`onboard --create-schema`](../deploy/multi-tenancy.md#what-it-provisions) bootstraps the database,
  the `flows` table and the [rollups](#rollups) as part of provisioning; without the flag, `onboard`
  requires the schema to exist and fails loudly if it does not (replicated clusters pre-create the
  table admin-side).

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

## Rollups

Alongside `flows` sit four **1-minute rollups**: `SummingMergeTree` tables kept up to date by
materialized views on `flows`. A dashboard that asks "top applications over the last 30 days"
reads a few thousand pre-aggregated rows instead of scanning every flow.

| table | dimensions (beyond the shared preamble) |
|---|---|
| `flows_by_application_1m` | `application`, `protocol` |
| `flows_by_conversation_1m` | `srcAddr`, `dstAddr`, `application` |
| `flows_by_exporter_iface_1m` | `exporterAddr`, `exporterName`, `inputSnmp`, `outputSnmp` |
| `flows_by_geo_asn_1m` | `srcAs`, `dstAs`, `srcCountry`, `dstCountry` |

Every rollup carries the same preamble — `tenant`, `organisation`, `timestamp`, `zone` — and the
same measures: `bytes`, `packets`, `flowCount`, plus the directional split `bytesIn`/`bytesOut`
and `packetsIn`/`packetsOut`. The undirected totals sit alongside the split deliberately: a flow
with `direction = UNKNOWN` counts in neither `bytesIn` nor `bytesOut`, so a query that summed the
split would quietly lose it. Use `bytes` unless you specifically want one direction.

`timestamp` keeps the raw table's column name, truncated to the minute
(`toStartOfMinute`), so a time filter ports between raw and rollup unchanged:

```sql
-- the same WHERE works against either table
SELECT application, sum(bytes) AS bytes
FROM riptide.flows_by_application_1m
WHERE timestamp >= now() - INTERVAL 7 DAY
GROUP BY application ORDER BY bytes DESC LIMIT 10;
```

Each rollup `X` is fed by a materialized view named `X_mv`. Query the table, never the `_mv`.

### Insert coalescing (`async-inserts`)

The collector inserts once per received packet, and every insert also feeds the four rollup
views. Without coalescing, that many small inserts collapse throughput on modest hardware —
measured on two cores: 206 inserts/s without the rollups, 56 with them, **607 with the rollups
and server-side coalescing** (`async_insert`, acknowledged on buffer append).

`riptide.clickhouse.async-inserts` controls it, and **unset follows `manage-schema`**:

- **manage mode → on.** No write barrier exists, and flow transport is lossy UDP anyway — the
  ~200 ms server-side buffer window changes nothing an operator relies on.
- **provisioned mode → off.** The insert is acknowledged before the server evaluates it, so a
  row the server later rejects is dropped **without the collector seeing an error** — including a
  mis-tenanted row failing the CHECK barrier. Isolation still holds (the row never lands), but
  the synchronous `469 VIOLATED_CONSTRAINT` signal is part of the provisioned-mode contract, so
  coalescing stays off unless you opt in.

Set the property explicitly to override either default.

### Retention

Rollups keep **365 days** by default, against the raw table's 30. That is the point of them: the
aggregates outlive the flows they came from, so long-range queries keep working after the raw
rows expire. Retention is set at creation time (`TTL timestamp + INTERVAL <n> DAY`) — in
provisioned mode via `onboard --ttl-days`, which applies to the raw table; adjust a rollup's TTL
with `ALTER TABLE … MODIFY TTL` if you need something different.

:::warning[Raw retention above 365 days inverts the invariant]

`--ttl-days` sets only the **raw** table's TTL — the rollups stay at 365 unless altered. A raw
retention above 365 therefore makes the rollups expire *before* the raw rows, silently defeating
"aggregates outlive the flows": long-range queries lose the oldest aggregates while the raw data
still exists. If you set `--ttl-days` above 365, raise the rollups to at least the same value:
`ALTER TABLE <db>.<rollup> MODIFY TTL timestamp + INTERVAL <n> DAY` for each rollup.

:::

:::warning[Materialized views do not backfill]

A rollup only covers traffic inserted **after** it was created. Adding the rollups to an existing
deployment does not populate them from historical `flows` rows — they start empty and fill from
that moment on. To backfill, `INSERT INTO … SELECT` from `flows` yourself, keeping the same
grouping the view uses.

:::

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
