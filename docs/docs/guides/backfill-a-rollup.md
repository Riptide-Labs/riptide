---
title: Backfill a rollup
sidebar_position: 11
description: Populate a 1-minute rollup from historical flows rows with an INSERT INTO … SELECT that names every column in the view's own order, and verify nothing was shifted or omitted.
---

# Backfill a rollup

A rollup only covers traffic inserted after its materialized view was created; a view does not backfill.
To cover older `flows` rows, insert them yourself with the grouping the view uses.

## Prerequisites

- The rollup and its view exist, see [Rollups](../architecture/rollups.md).
- A user with `SELECT` on `flows` and `INSERT` on the rollup target.
- The `flows` rows to backfill are still inside the raw retention window (30 days by default).
- A time range the view has never aggregated. A `SummingMergeTree` sums rows with the same key, so a range the view already covered would be counted twice.

## Steps

1. Read the view's own `SELECT`. It is the grouping the backfill must reproduce, and it changes when a release [adds a dimension](../architecture/rollups.md#rollups-gain-dimensions-in-place), so read it from the server rather than from this page.

   ```sql
   SELECT as_select FROM system.tables
   WHERE database = 'riptide' AND name = 'flows_by_application_1m_mv'
   FORMAT TSVRaw;
   ```

   Expected output:

   ```text
   SELECT f.tenant AS tenant, f.organisation AS organisation, toStartOfMinute(f.timestamp) AS timestamp, f.zone AS zone, ifNull(f.application, '') AS application, f.protocol AS protocol, f.samplingInterval AS samplingInterval, toString(f.flowProtocol) AS flowProtocol, sum(f.bytes) AS bytes, sum(f.packets) AS packets, count() AS flowCount, sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn, sumIf(f.bytes, f.direction = 'EGRESS') AS bytesOut, sumIf(f.packets, f.direction = 'INGRESS') AS packetsIn, sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut, groupBitOr(toUInt8(multiIf(f.samplingProvenance = 'record', 1, f.samplingProvenance = 'options', 2, f.samplingProvenance = 'header', 4, f.samplingProvenance = 'derived', 8, f.samplingProvenance = 'fallback', 16, f.samplingProvenance = 'assumed', 32, 0))) AS samplingProvenanceMask FROM riptide.flows AS f GROUP BY tenant, organisation, timestamp, zone, application, protocol, samplingInterval, flowProtocol
   ```

2. List the target's columns, so you can check that step 3 names them all.

   ```sql
   SELECT arrayStringConcat(groupArray(name), ', ') AS columns
   FROM (SELECT name, position FROM system.columns
         WHERE database = 'riptide' AND table = 'flows_by_application_1m' ORDER BY position);
   ```

   Expected output:

   ```text
   tenant, organisation, timestamp, zone, application, protocol, samplingInterval, flowProtocol, bytes, packets, flowCount, bytesIn, bytesOut, packetsIn, packetsOut, samplingProvenanceMask
   ```

3. Write the insert. Name the target columns, in the order of your `SELECT`, and bound the range with a `WHERE` on the raw `timestamp`.

   ```sql
   INSERT INTO riptide.flows_by_application_1m
       (tenant, organisation, timestamp, zone, application, protocol, samplingInterval, flowProtocol,
        bytes, packets, flowCount, bytesIn, bytesOut, packetsIn, packetsOut, samplingProvenanceMask)
   SELECT f.tenant AS tenant, f.organisation AS organisation, toStartOfMinute(f.timestamp) AS timestamp, f.zone AS zone,
          ifNull(f.application, '') AS application, f.protocol AS protocol, f.samplingInterval AS samplingInterval,
          toString(f.flowProtocol) AS flowProtocol,
          sum(f.bytes) AS bytes, sum(f.packets) AS packets, count() AS flowCount,
          sumIf(f.bytes, f.direction = 'INGRESS') AS bytesIn, sumIf(f.bytes, f.direction = 'EGRESS') AS bytesOut,
          sumIf(f.packets, f.direction = 'INGRESS') AS packetsIn, sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut,
          groupBitOr(toUInt8(multiIf(f.samplingProvenance = 'record', 1, f.samplingProvenance = 'options', 2,
                                     f.samplingProvenance = 'header', 4, f.samplingProvenance = 'derived', 8,
                                     f.samplingProvenance = 'fallback', 16, f.samplingProvenance = 'assumed', 32, 0))) AS samplingProvenanceMask
   FROM riptide.flows AS f
   WHERE f.timestamp >= toDateTime('2026-09-01 00:00:00') AND f.timestamp < toDateTime('2026-09-15 00:00:00')
   GROUP BY tenant, organisation, timestamp, zone, application, protocol, samplingInterval, flowProtocol;
   ```

   Expected output:

   ```text
   Ok.
   ```

   An `INSERT INTO … SELECT` without a column list is positional: ClickHouse matches by ordinal, never by name, and refuses a wrong count with `NUMBER_OF_COLUMNS_DOESNT_MATCH`.
   Riptide keeps an upgraded rollup in the same physical order as a fresh one by adding columns with `AFTER`, so a positional insert against a riptide-managed target is correct, but nothing in the statement tells you whether that held.
   Once you name the columns, the target's physical order stops mattering, and the order you write them in must match your `SELECT`: ClickHouse pairs the *n*th name with the *n*th expression.

4. Verify that no column was omitted.

   ```sql
   SELECT count() AS hand_written_rows
   FROM riptide.flows_by_application_1m
   WHERE samplingInterval = 0 AND flowProtocol != '';
   ```

   Expected output:

   ```text
   ┌─hand_written_rows─┐
   │                 0 │
   └───────────────────┘
   ```

   An omitted column takes its type default, so the rows land with `samplingInterval = 0`.
   A materialized view cannot produce a row with a rate of `0` and a known protocol, so that pair is the fingerprint of a hand-written backfill, and those rows escape the `WHERE samplingInterval > 0` predicate every corrected total uses, see [Query sampling-corrected volume](sampling-corrected-volume.md).

5. Verify the totals against the raw rows for the backfilled range.

   ```sql
   SELECT
       (SELECT sum(bytes) FROM riptide.flows
        WHERE timestamp >= toDateTime('2026-09-21 14:25:00') AND timestamp < toDateTime('2026-09-21 14:30:00')) AS raw_bytes,
       (SELECT sum(bytes) FROM riptide.flows_by_application_1m
        WHERE timestamp >= toDateTime('2026-09-21 14:25:00') AND timestamp < toDateTime('2026-09-21 14:30:00')) AS rollup_bytes;
   ```

   Expected output:

   ```text
   ┌─raw_bytes─┬─rollup_bytes─┐
   │ 389588452 │    389588452 │
   └───────────┴──────────────┘
   ```

A shift, as opposed to an omission, is usually loud: `flowProtocol` is a `LowCardinality(String)` between the dimensions and the `UInt64` measures, so a shift that reaches it fails with `CANNOT_PARSE_TEXT` rather than writing anything.
A shift is only silent where every column it crosses is numeric, and even then each column takes its neighbour's value, so a shifted `samplingInterval` holds another column's number, not `0`, and step 5 catches it.

## Related

- [Rollups](../architecture/rollups.md) for why a view does not backfill and how a rollup gains dimensions.
- [ClickHouse reference](../reference/clickhouse.md#rollups) for the table layout.

## Open questions

- The `INSERT` in step 3 was not run against a server while writing this page; its output is ClickHouse's usual `Ok.`. Steps 1, 2, 4 and 5 were run against ClickHouse 26.7.
