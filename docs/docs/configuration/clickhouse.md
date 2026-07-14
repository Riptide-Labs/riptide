---
sidebar_position: 4
title: ClickHouse
---

# ClickHouse

Enriched flows are persisted to ClickHouse:

```properties
riptide.clickhouse.endpoint=http://localhost:8123
riptide.clickhouse.username=default
riptide.clickhouse.password=
riptide.clickhouse.database=riptide
riptide.clickhouse.manage-schema=true
```

## Schema ownership

`riptide.clickhouse.manage-schema` (boolean, default `true`) selects who owns the schema:

- **`true` (default, single-tenant)** — riptide ensures the schema idempotently at startup:
  the `flows` table with `CREATE TABLE IF NOT EXISTS` (an existing table is not replaced, so
  its data survives), and the `samples` view with `CREATE OR REPLACE VIEW` (a view holds no
  data, so it is always refreshed and can never go stale). A fresh install is created; a
  restart keeps the data — so **flow data now survives a Riptide restart**.
- **`false` (provisioned / multi-tenant)** — riptide creates nothing. It validates that the
  `flows` table exists and carries every column it inserts and **fails startup with a clear,
  provisioning-pointing error** if it does not. Use this when an admin owns the schema and
  RBAC and each riptide process is a narrowly-scoped writer that only uses the table.

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
time-only (`toYYYYMMDD(timestamp)`).

:::note

`zone` replaces the former `riptide.location` key. `riptide.location` is deprecated but
still accepted for one release (mapped to `zone` with a warning); prefer
`riptide.identity.zone`.

:::
