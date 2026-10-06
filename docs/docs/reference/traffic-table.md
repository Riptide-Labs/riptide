---
title: Traffic table
sidebar_position: 10.5
description: The dashboard-first traffic table and its three 1-minute rollups, every column and what unknown looks like in it, how sampling and time are handled so a plain sum is correct, and queries to start a dashboard from.
---

# Traffic table

`traffic` is a second flow table written for people building dashboards and ad-hoc queries.
A plain `sum(bytes)` is the traffic on the wire for every protocol, no column is `Nullable` or an `Enum`, and the 1-minute rollups spread each flow over the minutes it covered.
It is the row contract later riptide collectors write too, so a dashboard built on it keeps working across collector versions.

It is off by default.
`flows` stays the table of record, and the MCP server and the shipped Grafana dashboards keep reading `flows`.

## Turn it on

```properties
riptide.clickhouse.traffic-table=true
```

At the next start riptide creates `traffic`, `traffic_by_interface_1m`, `traffic_by_application_1m`, `traffic_by_as_1m` and the materialized views feeding them, then writes every flow it stores in `flows` to `traffic` as well.
That is a second insert per batch.

It needs `riptide.clickhouse.manage-schema=true`.
`riptide onboard` does not yet create these tables, grant them or give them per-tenant row policies, so with `manage-schema: false` startup fails rather than write tenant rows into a table no row policy covers:

```text
riptide.clickhouse.traffic-table=true needs riptide.clickhouse.manage-schema=true: 'riptide onboard' does not yet create, grant or row-policy the traffic tables, so in a provisioned deployment they would hold every tenant's rows with no row policy. Turn traffic-table off, or let riptide manage the schema.
```

The write to `traffic` runs after the flows are committed to `flows`, and its failure is logged and counted on `persister.traffic.failedRows` rather than retried or dead-lettered.
A flow therefore can be in `flows` and missing from `traffic`, never the other way round.

Rows are written from the moment the switch is on.
Nothing is backfilled from `flows`.

## What makes a plain query correct

### `bytes` is sampling-corrected for every protocol

| Column | Meaning |
| --- | --- |
| `bytes`, `packets` | The estimate on the wire: the exporter's counters times its sampling rate. Sum these. |
| `bytes_reported`, `packets_reported` | What the exporter sent. |
| `sampling_rate` | The factor between the two pairs. `1` when the flow was unsampled or no rate was known. |
| `sampling_source` | Where the rate came from: `record`, `options`, `header`, `derived`, `fallback` or `assumed`. See [Sampling rates and provenance](../architecture/sampling.md). |

In `flows` a correct total needs `sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval))`, because sFlow counters arrive scaled and the others do not.
In `traffic` that rule is applied once, when the row is written, and `bytes = bytes_reported × sampling_rate` holds on every row.

The one exception is an estimate that does not fit in 63 bits.
It is written as `bytes = 0` with `bytes_reported` kept, because a saturated value would swamp every sum it is part of.
`WHERE bytes = 0 AND bytes_reported > 0` finds them.

### Unknown is `''`, `0` or `::`

No column is `Nullable`.
An interface name riptide could not resolve is `''`, an unknown AS is `0`, an absent next hop is `::`.
`WHERE in_if_name != ''` is all a filter needs.

Token columns are lower-case `LowCardinality(String)`:

| Column | Values |
| --- | --- |
| `flow_protocol` | `netflow5`, `netflow9`, `ipfix`, `sflow` |
| `direction` | `ingress`, `egress`, `unknown` |
| `src_locality`, `dst_locality` | `public`, `private`, or `''` |
| `application_source` | `exporter`, `rules`, `none` |

### Addresses are IPv6

Every address column is `IPv6`; IPv4 is stored IPv4-mapped, so `192.0.2.1` reads back as `::ffff:192.0.2.1`.
Compare with `toIPv6('192.0.2.1')`, which maps an IPv4 string the same way, and display with `replaceOne(toString(src_addr), '::ffff:', '')`.

### Time

| Column | Meaning |
| --- | --- |
| `time_start`, `time_end` | The interval this row's counters cover. For a long-lived flow under an active timeout, that is the interval since the previous export. Filter raw queries on `time_start`. |
| `flow_start` | When the flow itself began. |
| `time_received` | When riptide received the export, by riptide's clock. |

All are `DateTime64(3, 'UTC')`.

## The rollups

Each rollup is a `SummingMergeTree` with a `time` column of `DateTime('UTC')` at minute resolution, and three measures: `bytes`, `packets` and `flows`.

| Rollup | Dimensions after `tenant, organisation, time, zone` | Panels it serves |
| --- | --- | --- |
| `traffic_by_interface_1m` | `exporter_ip, exporter_name, in_if, in_if_name, out_if, out_if_name, direction` | interface utilisation |
| `traffic_by_application_1m` | `exporter_ip, application, proto` | application mix |
| `traffic_by_as_1m` | `src_as, src_as_name, dst_as, dst_as_name, src_country, dst_country` | peering and geography |

A flow covering several minutes is split across them in proportion to the time it spent in each.
A flow from 10:00:30 to 10:02:45 carrying 1,000,003 bytes gives 10:00 its 30 s (222,222 bytes), 10:01 its 60 s (444,446) and 10:02 its 45 s (333,335).
The shares are integer cuts that add up to the flow exactly, so a rollup's total equals the raw total, and `flows` counts each flow once, in the minute it ended.
A zero-length row such as an sFlow sample lands in its start minute.
So does a row claiming to span more than an hour, which is an exporter clock problem: spreading it would turn one row into thousands.

Rollups keep 365 days, `traffic` keeps 30.
Always aggregate a rollup with `sum()` and `GROUP BY`: rows with the same dimensions are only combined when ClickHouse merges parts, so a minute can be split over several rows until then.

## Queries to start from

The outputs below were captured on ClickHouse 26.7 from 600 synthetic rows written over 50 minutes by two exporters, `core-rtr1` sending IPFIX sampled 1 in 100 and `edge-sw1` sending sFlow sampled 1 in 512.
They show the shape of each result, not a real network.

Total traffic in the last hour, every protocol in one sum:

```sql
SELECT formatReadableSize(sum(bytes)) AS traffic, sum(packets) AS packets, count() AS flows
FROM riptide.traffic
WHERE time_start >= now() - INTERVAL 1 HOUR;
```

```text
165.65 MiB	331800	600
```

Interface utilisation in bits per second, one series per input interface:

```sql
SELECT time, in_if_name, round(sum(bytes) * 8 / 60) AS bps
FROM riptide.traffic_by_interface_1m
WHERE time >= now() - INTERVAL 1 HOUR AND exporter_name = 'core-rtr1'
GROUP BY time, in_if_name
ORDER BY time, in_if_name
LIMIT 4;
```

```text
2026-10-06 11:13:00	Gi0/1	27904
2026-10-06 11:13:00	Gi0/2	56010
2026-10-06 11:14:00	Gi0/1	36959
2026-10-06 11:14:00	Gi0/2	73830
```

In Grafana, replace the time condition with `$__timeFilter(time)`.

Application mix:

```sql
SELECT application, formatReadableSize(sum(bytes)) AS traffic, sum(flows) AS flows
FROM riptide.traffic_by_application_1m
WHERE time >= now() - INTERVAL 1 HOUR
GROUP BY application
ORDER BY sum(bytes) DESC;
```

```text
dns	55.22 MiB	200
https	55.22 MiB	200
ssh	55.22 MiB	200
```

Top source AS:

```sql
SELECT src_as, src_as_name, src_country, formatReadableSize(sum(bytes)) AS traffic
FROM riptide.traffic_by_as_1m
WHERE time >= now() - INTERVAL 1 HOUR
GROUP BY src_as, src_as_name, src_country
ORDER BY sum(bytes) DESC
LIMIT 5;
```

```text
64500	AS-EXAMPLE-A	DE	55.22 MiB
64502	AS-EXAMPLE-C	FR	55.22 MiB
64501	AS-EXAMPLE-B	US	55.22 MiB
```

Top talkers, from the raw table:

```sql
SELECT replaceOne(toString(src_addr), '::ffff:', '') AS source, formatReadableSize(sum(bytes)) AS traffic
FROM riptide.traffic
WHERE time_start >= now() - INTERVAL 1 HOUR
GROUP BY src_addr
ORDER BY sum(bytes) DESC
LIMIT 3;
```

```text
198.51.100.16	21.97 MiB
198.51.100.8	21.97 MiB
198.51.100.12	21.97 MiB
```

Which rate each exporter's traffic was scaled by, and where it came from:

```sql
SELECT exporter_name, flow_protocol, sampling_rate, sampling_source, count() AS flows
FROM riptide.traffic
WHERE time_start >= now() - INTERVAL 1 HOUR
GROUP BY ALL
ORDER BY exporter_name;
```

```text
core-rtr1	ipfix	100	record	450
edge-sw1	sflow	512	record	150
```

An exporter showing more than one `sampling_source` is not resolving its rate consistently; see [Query sampling-corrected volume](../guides/sampling-corrected-volume.md#find-exporters-whose-rate-is-not-resolving-consistently).

The raw table and every rollup agree on the total:

```sql
SELECT (SELECT sum(bytes) FROM riptide.traffic) AS raw,
       (SELECT sum(bytes) FROM riptide.traffic_by_interface_1m) AS by_interface,
       (SELECT sum(bytes) FROM riptide.traffic_by_application_1m) AS by_application,
       (SELECT sum(bytes) FROM riptide.traffic_by_as_1m) AS by_as;
```

```text
173700000	173700000	173700000	173700000
```

They stop agreeing once the raw table's 30-day TTL removes rows the rollups still hold.

## Every column

| Column | Type | Unknown |
| --- | --- | --- |
| `tenant`, `organisation`, `zone`, `system` | `LowCardinality(String)` | from `riptide.identity.*` |
| `time_start`, `time_end`, `flow_start`, `time_received` | `DateTime64(3, 'UTC')` | falls back to the next known time |
| `exporter_ip` | `IPv6` | `::` |
| `exporter_name` | `LowCardinality(String)` | `''` |
| `flow_protocol` | `LowCardinality(String)` | never |
| `observation_domain` | `UInt32` | `0` |
| `in_if`, `out_if` | `UInt32` | `0` |
| `in_if_name`, `in_if_alias`, `out_if_name`, `out_if_alias` | `LowCardinality(String)` | `''` |
| `in_if_speed`, `out_if_speed` | `UInt64` | `0` |
| `direction` | `LowCardinality(String)` | `unknown` |
| `src_addr`, `dst_addr`, `next_hop` | `IPv6` | `::` |
| `src_port`, `dst_port` | `UInt16` | `0` |
| `src_mask`, `dst_mask` | `UInt8` | `0` |
| `src_as`, `dst_as` | `UInt32` | `0` |
| `src_as_name`, `dst_as_name`, `src_country`, `dst_country`, `src_city`, `dst_city` | `LowCardinality(String)` | `''` |
| `src_host`, `dst_host` | `String` | `''`, and always `''` with reverse DNS off |
| `src_locality`, `dst_locality` | `LowCardinality(String)` | `''` |
| `ip_version`, `proto`, `tcp_flags`, `tos` | `UInt8` | `0` |
| `vlan` | `UInt16` | `0` |
| `application`, `application_source` | `LowCardinality(String)` | `''`, `none` |
| `application_id` | `UInt32` | `0` |
| `http_host`, `http_uri` | `String` | `''` |
| `bytes`, `packets`, `bytes_reported`, `packets_reported` | `UInt64` | never |
| `sampling_rate` | `Float64` | `1` |
| `sampling_source` | `LowCardinality(String)` | `assumed` |

The sort key is `(tenant, organisation, toStartOfHour(time_start), exporter_ip, in_if, src_addr, dst_addr)`, partitioned by day of `time_start`.

## Open questions

- The sort key leads with exporter and interface because most dashboard panels filter on them, where `flows` leads with AS and addresses. That is a judgement, not a measurement, and has not been checked against real dashboard queries on lab data.
- The second insert's cost on the collector and on ClickHouse has not been measured.
