---
title: Query sampling-corrected volume
sidebar_position: 10
description: Multiply stored counters by each row's sampling rate, against raw flows or a rollup, with the boundary predicates a rollup needs and the checks that tell you whether they apply.
---

# Query sampling-corrected volume

riptide stores `bytes` and `packets` as the exporter reported them and the sampling rate beside them.
A corrected total multiplies each row by its own protocol's factor: `1` for sFlow, whose counters arrive pre-scaled, and `samplingInterval` for everything else.
Why the rate is stored this way is on [Sampling rates and provenance](../architecture/sampling.md).

## Prerequisites

- Read access to the `riptide` database.
- For a query against a rollup: the rollups carry `samplingInterval` and `flowProtocol`. A deployment in validate mode (`manage-schema: false`, the multi-tenant default) gains them only when an admin re-runs `riptide onboard`; until then the rollup query fails with `UNKNOWN_IDENTIFIER` and riptide answers long-range queries from raw `flows`. See [Rollups](../architecture/rollups.md).
- If you are coming from nfdump or pmacct, check whether counters were being scaled for you. Both can multiply NetFlow v5 `bytes` and `packets` by the rate at ingest (pmacct via `nfacctd_renormalize`, nfdump depending on how sampling was detected or forced with `-s`). riptide never does, so a ported query may need the multiplication added rather than removed.

## Query raw `flows`

Use raw `flows` inside its retention window, 30 days by default.
No boundary predicate is needed: `flowProtocol` has been in the table since it was created, and a row older than `samplingInterval` reads the column default `1.0`.

```sql
SELECT sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval)) AS corrected_bytes
FROM riptide.flows
WHERE timestamp >= now() - INTERVAL 7 DAY;
```

Expected output:

```text
664481274
```

:::warning[Do not copy a rollup's boundary predicates onto raw `flows`]

`flowProtocol` in `flows` is an `Enum8` whose every member is a real protocol, so `flowProtocol != ''` fails the query outright:

```text
Code: 691. DB::Exception: Unknown element '' for enum: while converting '' to Enum8('NetflowV5' = 1, 'NetflowV9' = 2, 'IPFIX' = 3, 'SFLOW' = 4): while executing function notEquals on arguments __table1.flowProtocol Enum8('NetflowV5' = 1, 'NetflowV9' = 2, 'IPFIX' = 3, 'SFLOW' = 4) Int8(size = 0), ''_String String Const(size = 0, String(size = 1)). (UNKNOWN_ELEMENT_OF_ENUM) (version 26.7.3.19 (official build))
```

`samplingInterval > 0` is accepted but drops real rows: until the release that carried the rate into the rollups, an out-of-spec sFlow agent sending `0` produced raw rows with `samplingInterval = 0` and `bytes = 0`. A sum is unaffected, a `count()` or `sum(flowCount)` under that predicate is not.

:::

## Query a rollup

Use a rollup for a range that reaches past the raw retention; the rollups keep 365 days.
The scaling expression is the same.
The `WHERE` clause is not: a rollup dimension is appended to rows that already exist, and those rows read a reserved value, so two boundary predicates select the rows that carry both the rate and the protocol.

```sql
SELECT sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval)) AS corrected_bytes
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY
  AND samplingInterval > 0     -- excludes rows aggregated before the rate was carried
  AND flowProtocol != '';      -- excludes rows aggregated before the protocol was carried
```

Expected output:

```text
664481274
```

The two predicates are not redundant.
Without `samplingInterval > 0`, rows aggregated before the rate was carried contribute `bytes × 0` and the total comes back quietly too small.
Without `flowProtocol != ''`, rows aggregated after the rate but before the protocol read `''`, which is not `'SFLOW'`, so any sFlow inside that band is scaled by its rate and inflated.

## Size the bands before reading the total

On a deployment that upgraded recently, most of a 90-day window is excluded by the predicates, so check how many rows each band holds before reading the total as a fleet figure:

```sql
SELECT countIf(samplingInterval = 0)                            AS before_rate,
       countIf(samplingInterval > 0 AND flowProtocol =  '')     AS rate_only,
       countIf(samplingInterval > 0 AND flowProtocol != '')     AS both,
       count()                                                  AS total
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY;
```

Expected output:

```text
0	0	5268	5268
```

`both` uses the same pair of predicates as the corrected query, so it is exactly the row count that query reads.
The three counters sum to `total` on a rollup fed only by riptide.
If they do not, the rollup holds rows carrying `samplingInterval = 0` and a known protocol, which a materialized view cannot produce and a hand-written [backfill](backfill-a-rollup.md) can.
Bound the window: a rollup holds 365 days.

## Keep the one-predicate form on a deployment with no sFlow

If the deployment receives no sFlow, nothing in the `rate_only` band was ever wrong, and `flowProtocol != ''` would discard that band for up to 365 days to guard against traffic you do not receive.
Confirm inside the raw retention window:

```sql
SELECT count() FROM riptide.flows WHERE flowProtocol = 'SFLOW';
```

Expected output:

```text
0
```

If it is zero and your exporters have not changed, keep the form that predates the protocol column:

```sql
SELECT sum(bytes * samplingInterval) AS corrected_bytes
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY
  AND samplingInterval > 0;
```

Expected output:

```text
664481274
```

## Query the NetFlow and IPFIX subset only

`sum(bytes * samplingInterval) … WHERE samplingInterval > 0 AND flowProtocol NOT IN ('', 'SFLOW')` is a narrower question: corrected volume of the NetFlow and IPFIX rows.
It is short by every sFlow byte you receive, because it drops those rows rather than scaling them by `1`.
That is a legitimate query when you want the subset; it is not the corrected total.

```sql
SELECT sum(bytes * samplingInterval) AS corrected_bytes
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY
  AND samplingInterval > 0
  AND flowProtocol NOT IN ('', 'SFLOW');
```

Expected output:

```text
664481274
```

Keep the `''` in that exclusion list.
A bare `WHERE flowProtocol != 'SFLOW'` passes the middle-band rows, which carry `''`, and multiplies them by their rate, which reintroduces the exact inflation the protocol column exists to remove.

## Cast when a group exceeds 2^53 bytes

`samplingInterval` is a `Float64`, so the product is evaluated in floating point and the sum loses precision above 2^53, about 9 × 10^15.
That is about 9 petabytes in a single group-minute, so it is irrelevant at any realistic volume.
The drift is real but hidden by the formatter: at `bytes = 12345678901234567` and a rate of `1000`, ClickHouse 26.7 prints the float as `12345678901234567000`, while the value it holds is `12345678901234567168`, a drift of 168.
If you do query groups that large and need exactness, cast both factors first:

```sql
SELECT sum(toUInt128(bytes) * toUInt128(if(flowProtocol = 'SFLOW', 1, samplingInterval))) AS exact_bytes
FROM riptide.flows
WHERE timestamp >= now() - INTERVAL 7 DAY;
```

Expected output:

```text
664481274
```

This is not the recommended form: it costs more and a fractional rate would truncate.

## Find exporters whose rate is not resolving consistently

More than one provenance for one exporter means its rate is not resolving consistently: firmware that populates the sampling field on some export paths and not others, or a sampler options table that expires between refreshes.

```sql
SELECT exporterAddr, samplingProvenance, samplingInterval, count() AS flows
FROM riptide.flows
WHERE timestamp > now() - INTERVAL 7 DAY
GROUP BY exporterAddr, samplingProvenance, samplingInterval
ORDER BY exporterAddr, flows DESC;
```

Expected output:

```text
127.0.0.1	options	1	24557
127.0.0.1	assumed	1	15121
```

This query finds the condition after the fact; the `parser.optionSampling.*` counters on the [metrics reference](../reference/metrics.md) find it as it happens.

## Related

- [Sampling rates and provenance](../architecture/sampling.md) for why the rollups carry the rate and the protocol as dimensions.
- [Receivers reference](../reference/receivers.md) for the `samplingProvenance` and `flowProtocol` values.
- [Backfill a rollup](backfill-a-rollup.md) for the one operation that can put `samplingInterval = 0` beside a known protocol.

## Open questions

- Every output above was captured on 2026-09-23 from a local ClickHouse 26.7 holding 39,678 rows from one exporter with no sFlow and no rows aggregated before either column was carried, which is why the corrected totals agree across all four forms and the band counts are `0 0 5268 5268`. On a deployment that upgraded across those releases the forms diverge as the page describes.
