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
```

Riptide owns its schema: at startup it issues `CREATE OR REPLACE TABLE` for the `flows`
table.

:::warning

`CREATE OR REPLACE TABLE` **recreates the flows table on every start** — flow data does
not survive a Riptide restart in the current design.

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
