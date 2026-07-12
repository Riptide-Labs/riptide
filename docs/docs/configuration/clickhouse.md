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

Enrichment results are denormalized into the flow row at write time — exporter address
and location, resolved interface data (`inputSnmpIfName`/`ifAlias`/`ifSpeed` and the
`output…` counterparts), hostnames, classification, and locality — so queries never need
join-time lookups.
