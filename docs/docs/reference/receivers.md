---
title: Receivers reference
sidebar_position: 1
description: Every riptide.receivers.<name>.* key by receiver type, the sampling and timeout fallbacks, the session state bounds, and the samplingProvenance and flowProtocol values a flow can carry.
---

# Receivers reference

Receivers are the flow listeners.
None are configured by default, so the daemon starts no listener until you define one under **`riptide.receivers.<name>`**, where `<name>` is any free-form string.

## Common keys

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.receivers.<name>.type`** | string | required | `netflow5`, `netflow9`, `ipfix`, `sflow` or `multi`. Any other value fails startup naming the accepted values. |
| **`riptide.receivers.<name>.host`** | string | required | Bind address, for example `0.0.0.0`. |
| **`riptide.receivers.<name>.port`** | int | required | UDP port. An IPFIX receiver with `transport=TCP` listens on TCP instead. sFlow's conventional port is 6343. |

Setting a key on a receiver type that does not define it fails startup, because riptide rejects properties a receiver does not define.

## Keys by receiver type

| Name | Type | Default | Types | Description |
| --- | --- | --- | --- | --- |
| **`flow-active-timeout-fallback`** | duration | unset | `netflow9`, `ipfix`, `multi` | Active flow timeout used when the record carries none. An exporter that reports its own always wins. |
| **`flow-inactive-timeout-fallback`** | duration | unset | `netflow9`, `ipfix`, `multi` | Inactive flow timeout used when the record carries none. Set both fallbacks or neither: the delta-switched time is derived only when both timeouts resolve, so one alone changes nothing. |
| **`flow-sampling-interval-fallback`** | long | unset | `netflow5`, `netflow9`, `ipfix`, `multi` | Sampling interval recorded when nothing else states one. For v9 and IPFIX it ranks below the record and the sampler options table; for v5 it ranks below the packet header. Applies to every exporter on the receiver. |
| **`trust-header-sampling-interval`** | bool | `true` | `netflow5`, `multi` | Whether a NetFlow v5 header interval is read as a rate when the header's mode bits are neither 1 (deterministic) nor 2 (random). A header stating mode 1 or 2 with a non-zero interval is always read. |
| **`transport`** | `UDP` or `TCP` | `UDP` | `ipfix` | Transport the receiver listens on. |
| **`netflow5`**, **`netflow9`**, **`ipfix`**, **`sflow`** | bool | `true` | `multi` | Switch one of the four parsers off on a `multi` receiver. |

Durations take a suffix (`5m`, `30s`) or ISO-8601 (`PT5M`).
A bare number is milliseconds, so write `300s` rather than `300` for five minutes.

A `multi` receiver parses all four protocols on one port and tells them apart by their version words.

```yaml
riptide:
  receivers:
    ipfix:
      type: ipfix
      host: 0.0.0.0
      port: 4739
    nf9:
      type: netflow9
      host: 0.0.0.0
      port: 2055
      flow-active-timeout-fallback: 5m
      flow-inactive-timeout-fallback: 30s
    nf5:
      type: netflow5
      host: 0.0.0.0
      port: 2056
      flow-sampling-interval-fallback: 1000
```

The first receiver in `.properties` form:

```properties
riptide.receivers.ipfix.type=ipfix
riptide.receivers.ipfix.host=0.0.0.0
riptide.receivers.ipfix.port=4739
```

## Session state bounds

Per-exporter state on the UDP ingest path (templates, sequence trackers, exporter-pushed option records) is bounded by four keys under **`riptide.flows.session`**.
All four must be positive; zero or a negative value is refused at startup naming the key.
Why the bounds exist and what happens when one is reached is on [Exporter identity and session state](../architecture/session-state.md).

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.flows.session.max-sources`** | int | `4096` | Distinct UDP sources retaining state. New sources beyond it are refused; admitted ones keep their state. |
| **`riptide.flows.session.max-scopes-per-source`** | int | `16` | Scope identities per source. Beyond it, that source's least-recently-used scope is displaced. |
| **`riptide.flows.session.max-ifindexes-per-scope`** | int | `1024` | Interface entries per scope in the exporter option table. Beyond it, that scope's least-recently-used interface is evicted. |
| **`riptide.flows.session.source-idle-timeout`** | duration | `30m` | Silence after which a source's slot is released. Keep it at or above the receiver's template timeout; riptide warns at startup if the template timeout is longer. |

```yaml
riptide:
  flows:
    session:
      max-sources: 4096
      max-scopes-per-source: 16
      max-ifindexes-per-scope: 1024
      source-idle-timeout: 30m
```

Worst-case retained state multiplies out from those keys:

| Table | Worst case | Per single source |
| --- | --- | --- |
| Session tables | `max-sources × max-scopes-per-source × ~852 B` | |
| Interface table | `max-sources × max-scopes-per-source × max-ifindexes-per-scope × ~144 B` | `max-scopes-per-source × max-ifindexes-per-scope × ~144 B`, about 2.4 MB at the defaults |
| Application table | `max-sources × max-scopes-per-source × 16,384 × ~464 B` | `max-scopes-per-source × 16,384 × ~464 B`, about 122 MB at the defaults |

The application table's per-scope cap is fixed at 16,384 and is not a setting.
Its entry is the interface entry's ~144 B plus a name of at most 64 characters and a description of at most 255.

## `samplingProvenance` values

Every row of `flows` carries the rung of the resolution ladder that supplied its `samplingInterval`.
What the ladder is and why the column exists is on [Sampling rates and provenance](../architecture/sampling.md).

| Value | The rate came from |
| --- | --- |
| **`record`** | The flow record itself (NetFlow v9 or IPFIX fields 34, 49, 50), or an sFlow sample. |
| **`options`** | The exporter's sampler options table (NetFlow v9 or IPFIX). |
| **`header`** | The NetFlow v5 packet header. |
| **`derived`** | riptide's own arithmetic on the parameters of an IPFIX Selector Report. |
| **`fallback`** | The receiver's `flow-sampling-interval-fallback`. |
| **`assumed`** | Nothing stated a rate; `1` was recorded in the absence of one. |
| **`''`** (empty) | The row was written before the column existed. Not backfilled. |

riptide admits only finite intervals of `1.0` or more into the column and records `1.0` when nothing states one, so `0` is never a rate it produces.

## `flowProtocol` values

| Table | Column type | Values |
| --- | --- | --- |
| **`flows`** | `Enum8` | `NetflowV5`, `NetflowV9`, `IPFIX`, `SFLOW`. Comparing the column against `''` is an error (`UNKNOWN_ELEMENT_OF_ENUM`). |
| **`flows_by_*_1m`** rollups | `LowCardinality(String)` | The same four names, plus `''` on rows aggregated before the column was carried. |

## Related

- [Sampling rates and provenance](../architecture/sampling.md) for how a rate resolves per protocol.
- [Exporter identity and session state](../architecture/session-state.md) for what the bounds protect.
- [Query sampling-corrected volume](../guides/sampling-corrected-volume.md) for the SQL that applies the rate.
- [Metrics reference](metrics.md) for the `parser.optionSampling.*`, `parser.options.*` and `flows.session.*` families.
