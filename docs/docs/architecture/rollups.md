---
title: Rollups
sidebar_position: 8
description: What the four 1-minute rollups are for, how a rollup gains a dimension in place without a hole, what riptide refuses to repair, why the rollups outlive the raw rows, and what a query gets when a rollup is declined.
---

# Rollups

A dashboard that asks "top applications over the last 30 days" should read a few thousand pre-aggregated rows instead of scanning every flow.
Four `SummingMergeTree` tables beside `flows` answer that, each kept up to date by a materialized view on `flows`.
The table layout and measures are on the [ClickHouse reference](../reference/clickhouse.md#rollups).

## Components

| Component | Responsibility | Talks to |
| --- | --- | --- |
| `flows` | Raw rows, 30-day TTL | Fed by the collector |
| `flows_by_*_1m_mv` | Materialized view; runs as the inserting user on every insert into `flows` | Writes to its target |
| `flows_by_*_1m` | `SummingMergeTree` target, 365-day TTL | Read by dashboards and the MCP tools |
| `samples(ival)` | View that expands each flow into per-bucket rows | Reads `flows` |
| Rollup shape check | At startup, compares each rollup with the shape this version emits and declines drifted ones | Reads `system.tables` and `system.columns` |

## Rollup or samples()

The `samples(ival)` view expands each flow into per-bucket rows, attributing bytes and packets to each bucket proportionally to the time the flow spent in it, assuming an even rate across `deltaSwitched` to `lastSwitched`.
Summing over buckets always returns the flow's exact totals.
This is the `time-proportional` load scheme SiLK's `rwcount` defaults to; the usual alternative, bucketing each flow entirely into its start minute, spikes long flows into single buckets.
The precision has a query-time cost: every query over `samples()` processes roughly flows × buckets-per-flow rows.

A rollup attributes each flow to its start minute.
A `samples(ival = 60)` query and a rollup query therefore differ in attribution but usually not in what a dashboard shows, and the rollup reads thousands of rows instead of exploding millions.
Reach for `samples()` when you need sub-minute buckets or exact proportional attribution, and follow [Tune queries over samples()](../guides/tune-samples-queries.md) when you do.

## A rollup gains dimensions in place {/* #rollups-gain-dimensions-in-place */}

When a release adds a dimension to a rollup, riptide appends it to the existing table rather than leaving upgraded deployments on the old shape.
Two statements per rollup, both metadata-only: one `ALTER` that adds the column and extends the sorting key, then `MODIFY QUERY` on the materialized view.

No aggregation is interrupted.
`MODIFY QUERY` swaps the view's SELECT in place.
Dropping and recreating the view would leave a window in which nothing is aggregated, and a materialized view does not backfill, so that window would be a permanent hole in the rollup; measured at 0.44 % of flows at a modest ingest rate.
Riptide does not take that path.

Rows aggregated before the append read the column's type default, `0` for a number and `''` for a string, because a column joining the sorting key cannot be given a `DEFAULT`.
That is the boundary: it marks exactly which rows predate the change, and a query spanning an upgrade can exclude them with a predicate rather than needing to remember a date.
The rollups also carry `samplingInterval` and `flowProtocol` for exactly this reason, so sampling-corrected volume stays answerable beyond the raw table's retention; the predicates a query needs are on [Sampling rates and provenance](sampling.md).

Repair runs in manage mode at startup, and in `riptide onboard` for provisioned deployments.
It is idempotent, so it runs on every start and does nothing after the first.
Riptide logs a line naming the rollup and the key change when it repairs something, and stays silent otherwise.
A validate-mode collector plans no repairs: until `onboard` is re-run it reports all four rollups as not matching this version and declines them at query time, and the decision is made once, at startup, so the collector must be restarted after the repair.
The procedure is in [Upgrade riptide](../guides/upgrade.md).

A measure is added in place when the engine does not sum it.
The sampling-provenance bitmask is `SimpleAggregateFunction(groupBitOr, …)`, so a historical row reading `0` means "no provenance recorded" rather than a total that is too small.
Riptide adds it to the existing table in manage mode and in `onboard`, with no loss and no rebuild.
The distinction is the aggregation, not the fact that it is a measure.

## What riptide will not do in place

| Change | Why it is refused | What happens |
| --- | --- | --- |
| Shrink a sorting key | The grain would change and existing rows would not be re-aggregated | Reported; the rollup is left alone. `onboard` reads each rollup's live sorting key first and applies the same rule, because ClickHouse itself accepts such a shrink on an upgraded table |
| Repair a corrected aggregate | Repairing would readmit rows computed the old way with nothing to distinguish them | Declined at query time, see [Recover from a rollup shape message](../operations/rollup-drift.md) |
| Add a summed measure | A summed measure reading `0` for historical rows makes a `SUM` spanning the upgrade quietly too small, with nothing in the data marking where. Dimensions have a boundary; summed measures do not | In manage mode and in `onboard`, riptide refuses the rollup and logs `Rollup X left as it is: measure [...] is missing`, naming the remedy. A validate-mode collector only reports the drift and declines the rollup. The remedy discards that rollup's aggregated history: drop the rollup's view and target table, then restart a manage-mode collector, or re-run `riptide onboard` and restart the collector after it |

:::warning[Do not drop the rollup materialized views when rolling back]

Riptide does not support downgrading.
With the views in place an older riptide leaves them alone and keeps aggregating correctly into columns it does not read.
With them gone, it either stops feeding the rollup entirely or creates a narrow view over the still-wide target, writing the reserved value into a sorting-key column for the rollup's full 365-day retention.

:::

## Why the shape check exists

`CREATE MATERIALIZED VIEW IF NOT EXISTS` does nothing against a view that already exists.
A deployment that has started riptide once would keep its original rollup shape indefinitely, so a rollup gaining a dimension or a measure in a new release would reach a fresh install and not an upgraded one, with nothing failing and nothing logged.
Riptide therefore compares every rollup against the shape the running version intends, in both schema modes, and reports what it finds.
The check itself changes nothing; the in-place repair is a separate step that runs before it, in manage mode and in `onboard` only.
The four messages and their remedies are the [rollup drift runbook](../operations/rollup-drift.md).

Ingestion is unaffected by a declined rollup: raw `flows` still receives every flow, and a rollup is a query-path optimisation, not a collection path.
Long-range queries stop using that rollup and are answered from raw `flows`; the other rollups keep serving.

## The fallback is bounded by raw retention {/* #the-fallback-is-bounded-by-raw-retention */}

Raw `flows` is kept for 30 days by default; the rollups for 365.
Part of why the rollups exist is that long-range queries outlive the raw table's expiry.
So a query that falls back and reaches further back than the raw retention comes back incomplete, not merely slower: it returns the rows that still exist.

A short answer says it is short.
When the table that answered cannot reach back to the start of the range, the result carries one extra entry naming the table, the earliest data it holds, and how much of the range that covers:

```text
coverage_warning: answered from riptide.flows, which holds data from
                  2026-08-19 09:14:02. This answer covers 10080 of the
                  43200 minutes you asked for, which is what this table
                  retains. The rest is not missing from your network.
```

A fully covered answer is unchanged, so the entry means something when it appears.
The coverage figure is read from the data, not from a retention setting: a deployment provisioned with `onboard --ttl-days 7` is told about 7 days rather than riptide's 30-day default, and a rollup that only began aggregating last Tuesday is honest about holding less than its 365-day TTL permits.
Two things can shorten an answer and both are reported: the table holding less than you asked for, and riptide's own cap of 43200 minutes on a single query.
It reports a shortfall it can observe at the start of the range; a table with a gap in the middle still answers with that gap, and nothing here detects it.

Treat a drifted rollup as something to repair promptly rather than to live with, and until then keep queries against it inside the raw retention window.

## Why the rollups outlive the raw rows

Rollups keep 365 days by default against the raw table's 30.
The aggregates outlive the flows they came from, so long-range queries keep working after the raw rows expire.
Retention is set at creation time; the values and how to change them are on the [ClickHouse reference](../reference/clickhouse.md#retention).

A rollup only covers traffic inserted after it was created.
Adding the rollups to an existing deployment does not populate them from historical `flows` rows; they start empty and fill from that moment on.
Backfilling is a hand-written `INSERT INTO … SELECT` with the view's own grouping, see [Backfill a rollup](../guides/backfill-a-rollup.md).

## Trade-offs

| Choice | Gained | Given up |
| --- | --- | --- |
| Start-minute attribution in the rollups | Thousands of rows per dashboard query | Proportional attribution of long flows; `samples()` keeps that |
| Append dimensions with `MODIFY QUERY` | No hole in the aggregate | Pre-upgrade rows read a reserved value and need a predicate |
| Refuse to add a summed measure in place | No quietly-too-small totals | That rollup's history when the remedy is applied |
| Decline a drifted rollup at query time | Never a wrong answer from a wrong shape | Answers bounded by raw retention until the repair |
| 365-day rollup TTL against 30 raw | Long-range queries after raw expiry | Inverted if `--ttl-days` exceeds 365 without altering the rollups |
