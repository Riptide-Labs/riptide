---
title: Tune queries over samples()
description: Keep a query over the samples() view fast by preferring a rollup at minute buckets, bounding both the flow interval and the raw record time, and rendering labels after aggregating.
---

# Tune queries over samples()

Every query over `samples()` processes roughly flows × buckets-per-flow rows, because the view expands each flow into one row per bucket.
Three habits keep that cost small.

## Prerequisites

- A ClickHouse with the new query analyzer enabled, which it is by default on the [tested versions](../reference/clickhouse.md#server-versions).
- `SELECT` on `flows`, the rollups and the `samples` view.

## Steps

1. Use a rollup when the buckets are a minute or coarser. A `samples(ival = 60)` query and a rollup query differ in attribution, proportional against start-minute, but usually not in what a dashboard shows, and the rollup reads thousands of rows instead of exploding millions. Reach for `samples()` only for sub-minute buckets or exact proportional attribution.

   ```sql
   -- the same WHERE works against either table
   SELECT application, sum(bytes) AS bytes
   FROM riptide.flows_by_application_1m
   WHERE timestamp >= now() - INTERVAL 7 DAY
   GROUP BY application ORDER BY bytes DESC LIMIT 10;
   ```

   Expected output:

   ```text
   ┌─application────┬─────bytes─┐
   │ https          │ 478494402 │
   │ shilp          │ 125477552 │
   │                │  27140197 │
   │ pdl-datastream │  17677323 │
   │ http           │   3634316 │
   │ ssh            │   2993540 │
   │ us-cli         │   1839315 │
   │ domain         │   1792648 │
   │ distinct       │   1468376 │
   │ matahari       │   1428485 │
   └────────────────┴───────────┘
   ```

2. Bound the flow interval and the raw record time, and keep top-N mapping on raw keys. Inside the view the bucketed `timestamp` shadows the raw column, so neither `WHERE timestamp …` nor bounds on `deltaSwitched` and `lastSwitched` can use the table's partition or primary index; those bounds only filter rows before the explosion, which is still worthwhile. The raw, indexed column stays addressable as `flow.timestamp`: bound it too, widened by the longest flow duration you expect, and the query prunes partitions like a direct table read.

   ```sql
   -- top-10 source AS as bps series over five minutes at 60 s buckets
   WITH top AS (
       SELECT srcAs FROM riptide.flows
       WHERE timestamp >= toDateTime('2026-09-21 14:25:00') AND timestamp <= toDateTime('2026-09-21 14:30:00')
         AND lastSwitched >= toDateTime('2026-09-21 14:25:00') AND deltaSwitched <= toDateTime('2026-09-21 14:30:00')
       GROUP BY srcAs ORDER BY sum(bytes) DESC LIMIT 10
   )
   SELECT time, if(asn IS NULL, 'Other', toString(asn)) AS series, b   -- labels: hundreds of rows
   FROM (
       SELECT timestamp AS time,
              if(srcAs IN top, toNullable(srcAs), NULL) AS asn,        -- raw-key mapping: millions of rows
              sum(bytes) * 8 / 60 AS b
       FROM riptide.samples(ival = 60)
       WHERE timestamp >= toDateTime('2026-09-21 14:25:00') AND timestamp <= toDateTime('2026-09-21 14:30:00')
         AND lastSwitched >= toDateTime('2026-09-21 14:25:00') AND deltaSwitched <= toDateTime('2026-09-21 14:30:00')   -- pre-explosion row filter
         AND flow.timestamp >= toDateTime('2026-09-21 14:25:00') - INTERVAL 1 HOUR   -- partition and index pruning on the
         AND flow.timestamp <= toDateTime('2026-09-21 14:30:00') + INTERVAL 1 HOUR   -- raw column, widened by max flow duration
       GROUP BY time, asn
   ) ORDER BY time;
   ```

   Expected output:

   ```text
   ┌──────────────────────────time─┬─series─┬──────────────────b─┐
   │ 2026-09-21 14:25:00.000000000 │ 65423  │          1677911.6 │
   │ 2026-09-21 14:26:00.000000000 │ 65423  │ 1881021.8666666667 │
   │ 2026-09-21 14:27:00.000000000 │ 65423  │ 1789946.5333333334 │
   │ 2026-09-21 14:28:00.000000000 │ 65423  │ 1622516.2666666666 │
   │ 2026-09-21 14:29:00.000000000 │ 65423  │ 44973730.666666664 │
   │ 2026-09-21 14:30:00.000000000 │ 65423  │ 29193.333333333332 │
   └───────────────────────────────┴────────┴────────────────────┘
   ```

   `NULL` is the 'Other' sentinel because it lies outside the value space: an unknown AS arrives as `srcAs = 0`, which is a real series, often the largest, and must not be folded into 'Other'.

3. Render labels after aggregating, not per row. The inner `GROUP BY` above runs over the exploded rows, millions of them, so group on raw binary columns (addresses, AS numbers, tuples) and build display strings (`IPv6NumToString`, `concat`, hostname fallbacks) in the outer select, which sees only a few hundred aggregated rows. For high-cardinality keys such as conversations this alone is worth about 1.5×; formatted-string group keys also force ClickHouse's slowest aggregation path.

4. Fill gaps when a panel needs them. The view does not emit zero rows for empty buckets; use `ORDER BY time WITH FILL STEP <ival>` or Grafana's null-as-zero option.

   ```sql
   SELECT toStartOfMinute(timestamp) AS time, sum(bytes) AS bytes
   FROM riptide.flows_by_application_1m
   WHERE timestamp >= toDateTime('2026-09-21 14:18:00') AND timestamp < toDateTime('2026-09-21 14:24:00')
   GROUP BY time
   ORDER BY time WITH FILL FROM toDateTime('2026-09-21 14:18:00') TO toDateTime('2026-09-21 14:24:00') STEP 60;
   ```

   Expected output:

   ```text
   ┌────────────────time─┬────bytes─┐
   │ 2026-09-21 14:18:00 │        0 │
   │ 2026-09-21 14:19:00 │        0 │
   │ 2026-09-21 14:20:00 │  4869417 │
   │ 2026-09-21 14:21:00 │ 32638460 │
   │ 2026-09-21 14:22:00 │ 26847096 │
   │ 2026-09-21 14:23:00 │ 13328169 │
   └─────────────────────┴──────────┘
   ```

## Related

- [Rollups](../architecture/rollups.md#rollup-or-samples) for how the two attribution models differ.
- [ClickHouse reference](../reference/clickhouse.md#rollups) for the rollup tables.

## Open questions

- The bundled top-10 dashboards predate step 3 and still render their series labels per exploded row; rewriting them is tracked in [#346](https://github.com/Riptide-Labs/riptide/issues/346).
