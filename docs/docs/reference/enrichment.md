---
title: Enrichment reference
sidebar_position: 5
description: Every key that tunes SNMP polling, exporter option tables, reverse DNS, classification and clock correction, the retired keys, the IF-MIB columns riptide reads, and the columns enrichment writes.
---

# Enrichment reference

The design behind these keys is on the [enrichment](../architecture/enrichment.md) page.

## SNMP polling

Per-profile cadence, under **`riptide.snmp.polling.<name>`**.
The profile named `default` applies to every agent range that names none.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.snmp.polling.<name>.refresh-interval`** | duration | `10m` | How often each exporter in a range using this profile is walked. Zero or negative fails startup naming the profile. |
| **`riptide.snmp.polling.<name>.snapshot-expiry`** | duration | `30m` | How long a snapshot stays usable after its walk. A value shorter than `refresh-interval` logs a startup warning and leaves enrichment blank between walks. Zero or negative fails startup. |

Fleet-level keys, under **`riptide.snmp.poll`**:

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.snmp.poll.pool-width`** | int | `4` | Walks in flight across the whole fleet. Per-endpoint concurrency is always 1. |
| **`riptide.snmp.poll.deregister-after`** | int | `3` | Silent refresh intervals after which an exporter stops being polled. |
| **`riptide.snmp.poll.dead-endpoint-base-ms`** | long (ms) | `60000` | First retry delay after a failed walk. Doubles on each failure. |
| **`riptide.snmp.poll.dead-endpoint-ceiling-ms`** | long (ms) | `1800000` | Upper bound on the retry delay. |
| **`riptide.snmp.poll.max-exporters`** | int | `4096` | Bound on retained snapshots, counted in exporters. Registration follows flow arrival, so the population is whatever sends flows, including a spoofed source. |

Exporter-pushed option tables (interface and application) share one retention:

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.snmp.options.retention-ms`** | long (ms) | `1200000` | How long an exporter-pushed interface or application table entry is kept after it was last advertised. The application table has no separate key. |

The application table's per-scope cap is fixed at 16,384 ids and its name length at 64 characters; neither is configurable.

### Retired keys

| Key | Effect when set | Replacement |
| --- | --- | --- |
| **`riptide.snmp.poll.refresh-interval-ms`** | fails startup, in any file or environment spelling | `riptide.snmp.polling.<name>.refresh-interval` |
| **`riptide.snmp.poll.snapshot-expiry-ms`** | fails startup | `riptide.snmp.polling.<name>.snapshot-expiry` |
| **`riptide.snmp.cache.retention-ms`** | ignored, with a startup warning | `riptide.snmp.polling.<name>.refresh-interval`. Not carried over: the old value was a cache TTL, the new one is a poll interval. See [Upgrade riptide](../operations/upgrades/upgrade.md). |
| **`riptide.snmp.cache.negative-retention-ms`** | ignored, with a startup warning | none. An `ifIndex` absent from a polled snapshot is a known absence. |
| **`riptide.snmp.cache.dead-endpoint-retention-ms`** | ignored, with a startup warning | `riptide.snmp.poll.dead-endpoint-base-ms` and `-ceiling-ms` |

Riptide logs a warning at startup for each ignored key it finds set, so a stale configuration file is loud rather than silently ineffective.

## IF-MIB columns

| Resolved | IF-MIB source | Notes |
| --- | --- | --- |
| **`…IfName`** | `ifName` (ifXTable), `ifDescr` fallback (legacy ifTable) | short interface name, for example `Eth1/0` |
| **`…IfAlias`** | `ifAlias` (ifXTable) | the operator-assigned label; unlike `ifIndex` it is stable across device reboots (RFC 2863) |
| **`…IfSpeed`** | `ifHighSpeed` (ifXTable) | Mbit/s |

`…` is `inputSnmp` or `outputSnmp`, matching the flow's `INPUT_SNMP`/`OUTPUT_SNMP` (NetFlow v9) or `ingressInterface`/`egressInterface` (IPFIX) value.

## Reverse DNS

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.enricher.hostnames.enabled`** | bool | `false` | Resolve `srcAddr`, `dstAddr` and `nextHop` to hostnames through asynchronous PTR lookups. The bundled `application.properties` sets `false`; the class default is `true`. |

## Classification

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.classification.rules`** | Spring resource | `classpath:classification-rules.csv` | The ruleset. `file:` and `http(s)://` locations are accepted. An unreadable or unparseable resource fails startup. |
| **`riptide.classification.reload-interval`** | duration | `0` (disabled) | Poll the resource on this schedule and apply a changed ruleset without a restart. Absent or `0` parses the rules once at boot. |

The rule format and the reload procedure are in [Write a classification rule](../operations/classification-rules.md).

## Clock correction

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.enricher.clock-correction.enabled`** | bool | `true` | Registers the enricher. `false` removes both the ordering repair and the skew correction. |
| **`riptide.enricher.clock-correction.skew-threshold-ms`** | long (ms) | `0` | Skew at or above which every time column is shifted by the negative skew. `0` disables skew correction; only the ordering repair runs. |

## Columns enrichment writes

| Column | Type | Written when | Value |
| --- | --- | --- | --- |
| **`application`** | string | a rung of the application ladder named the flow | the exporter's name or the rule's name; null when nothing matched |
| **`applicationId`** | integer | every row | `engine << 24 \| selector` from IE 95, `0` when the record carried none |
| **`applicationSource`** | string | every row | `exporter`, `rules` or `none`; `''` on rows written before the column existed |
| **`applicationDescription`** | string | `applicationSource` is `exporter` | the exporter table's description; `''` otherwise |
| **`httpHost`** | string | a Cisco AVC request record carried PEN 9 element 12235 | the hostname after the six-byte prefix; `''` for a response record, a record without the element, or a row that predates the column |
| **`httpUri`** | string | a Cisco AVC request record carried PEN 9 element 9357 | the URI with the highest hit count, first on a tie; `''` under the same conditions as `httpHost` |
| **`clockCorrection`** | nullable integer | skew correction shifted the row | the negated skew; null otherwise |

The rollups carry `application` but not `applicationId`, `applicationSource` or `applicationDescription`.

## Metrics

The enrichment meters (`enrichment.optionApplications.*`, `enrichment.application.unresolved`, `classification.rules.*`) are listed on the [metrics reference](metrics.md).

## Open questions

- `riptide.enricher.hostnames.*` carries further keys in `HostnamesConfig` (cache TTLs, resolver threads, nameservers, a circuit breaker) that no page documents.
