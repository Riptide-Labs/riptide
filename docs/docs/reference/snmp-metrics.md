---
title: SNMP metrics reference
sidebar_position: 15
description: The remote-write sink settings, the polling profile's collect key, the fifteen interface series it produces, and every validation and log message either can raise.
---

# SNMP metrics reference

Riptide can turn the same SNMP walk that feeds interface-name enrichment into a Prometheus remote-write stream of interface counters.
Turning it on takes two settings: `riptide.metrics.remote-write.url` to point at a receiver, and `collect: [if-mib-interfaces]` on the polling profiles that should walk counters, not just names.
How the walk itself is scheduled, spread and backed off is in [SNMP agent reference](agent-configuration.md); this page covers only what is new for metrics.

## Settings

Every key below is under `riptide.metrics.remote-write.` except the last, which is a polling profile key.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.metrics.remote-write.url`** | URL | unset | The full write URL, tenant path included for a VictoriaMetrics cluster. Unset disables the sink; samples then go nowhere. A value that is not an `http` or `https` URL fails startup. |
| **`riptide.metrics.remote-write.bearer-token`** | [secret reference](secret-references.md) | unset | Resolved on every flush, so a rotation takes effect without a restart. Sent as `Authorization: Bearer <token>`. Unset sends no `Authorization` header. |
| **`riptide.metrics.remote-write.batch.max-samples`** | int | `5000` | Samples per flushed batch. Must be positive. |
| **`riptide.metrics.remote-write.batch.max-latency`** | duration | `2s` | A batch flushes when this elapses, even under `max-samples`. Must be positive. |
| **`riptide.metrics.remote-write.queue-capacity`** | int | `2000000` | Bounded queue depth. A sample offered while the queue is full is dropped and counted on `metrics.sink.droppedSamples`. Must be positive. |
| **`riptide.metrics.remote-write.shutdown-grace-period`** | duration | `5s` | Time given to drain the queue at shutdown; anything left when it expires is dropped as failed. Must be at least twice `batch.max-latency`. |
| **`riptide.metrics.remote-write.max-attempts`** | int | `3` | Attempts, including the first, before a batch is dropped as failed. Retried on HTTP 429, 5xx, or a connection failure; any other non-2xx drops the batch without retrying. Must be positive. |
| **`riptide.metrics.remote-write.retry-backoff`** | duration | `1s` | Delay before the first retry. Doubles on each further attempt: attempt 2 waits this long, attempt 3 waits twice that, and so on. Must be positive. |
| **`riptide.snmp.polling.<name>.collect`** | list of strings | empty | Collection names this profile walks for counters, in addition to interface-name enrichment. `if-mib-interfaces` is the only accepted value; an unknown name fails the bind at startup. Non-empty makes `refresh-interval` the counter interval, and the walk budget becomes 80 percent of it: `timeout x (retries + 1)` must fit inside that budget, or the profile fails to load. |

An internal certificate authority for the write URL goes in `riptide.http.ca-bundle`, documented once on [Outbound TLS](outbound-tls.md); it is not repeated here.
Requests carry `Content-Type: application/x-protobuf`, `Content-Encoding: snappy` and `X-Prometheus-Remote-Write-Version: 0.1.0`.

## Size the permits

`riptide.snmp.poll.pool-width` defaults to `4`.
It bounds the walks in flight across the whole fleet, not per agent.
With `collect` set, the permits needed are about walks per second times walk latency.
For example, 5,000 devices at a 60 s `refresh-interval` is about 83 walks per second.
At 200 ms per walk that needs about 17 permits, so `32` is a safe start.
`riptide.snmp.poll.suspect-pool-width` is a separate budget for agents whose last walk failed, so it does not come out of this figure.
A due walk that finds every permit busy waits in a due queue and starts the moment one frees, in due order, so no device can be skipped for ever.
An inventory-registered device's first walk is spread across the interval by the same address-derived offset as its re-walks, so a restart with thousands of `poll: always` entries produces its first sweep at the steady rate rather than as one burst.
Watch `snmp.poller.deferred` and `snmp.poller.inFlight` on `/metrics`.
A sustained `deferred` rate while `inFlight` is pinned at `pool-width` means the permits are too few.
Both keys are described on the [SNMP agent reference](agent-configuration.md).

## Series

Every series below carries the labels `tenant`, `organisation`, `zone`, `exporter`, `exporter_address`, `ifIndex`; `riptide_interface_info` additionally carries `ifAlias` and `ifHighSpeed`.
`ifName` is present only when the device answers ifXTable; the info columns (`ifName`, `ifAlias`, `ifHighSpeed`) all live there, and a row that ifXTable never answered for carries none of them.
`tenant`, `organisation` and `zone` come from `riptide.identity.*`, each defaulting to `default`.
`exporter` is the inventory entry name when one covers the address, otherwise the address itself; `exporter_address` is always the address.
Counters are the raw monotonic value the agent reports; a reboot resets them, so read them with PromQL `rate()` rather than as an absolute level.

| Name | Type | Meaning |
| --- | --- | --- |
| **`ifHCInOctets`** | counter | ifXTable inbound octets |
| **`ifHCOutOctets`** | counter | ifXTable outbound octets |
| **`ifHCInUcastPkts`** | counter | ifXTable inbound unicast packets |
| **`ifHCOutUcastPkts`** | counter | ifXTable outbound unicast packets |
| **`ifHCInMulticastPkts`** | counter | ifXTable inbound multicast packets |
| **`ifHCOutMulticastPkts`** | counter | ifXTable outbound multicast packets |
| **`ifHCInBroadcastPkts`** | counter | ifXTable inbound broadcast packets |
| **`ifHCOutBroadcastPkts`** | counter | ifXTable outbound broadcast packets |
| **`ifInErrors`** | counter | ifTable inbound errors |
| **`ifOutErrors`** | counter | ifTable outbound errors |
| **`ifInDiscards`** | counter | ifTable inbound discards |
| **`ifOutDiscards`** | counter | ifTable outbound discards |
| **`ifOperStatus`** | gauge | ifTable operational status, the numeric IF-MIB code |
| **`ifAdminStatus`** | gauge | ifTable administrative status, the numeric IF-MIB code |
| **`riptide_interface_info`** | gauge | always `1`; carries `ifAlias` and `ifHighSpeed` (Mbit/s) as extra labels, for a join against the counters above by `exporter` and `ifIndex` |

A device that answers ifTable but not ifXTable produces `ifInErrors`, `ifOutErrors`, `ifInDiscards`, `ifOutDiscards`, `ifOperStatus` and `ifAdminStatus` only, each without an `ifName` label.
The eight octet and packet counters above do not exist for it, `riptide_interface_info` is never emitted for it either (it carries no row with an info column to build one from), and the poller logs a warning once per registration rather than once per walk.

## VictoriaMetrics cluster URL

A VictoriaMetrics cluster carries the tenant in the write path itself, not in a label: `http://vminsert:8480/insert/<accountID>:<projectID>/prometheus/api/v1/write`.
Single-node VictoriaMetrics, and plain Prometheus remote-write receivers, use `/api/v1/write` with no tenant segment; separate tenants there by the `tenant` and `organisation` labels every series already carries.

## Deduplication

Two collectors walking the same device produce two samples at the same series and roughly the same timestamp, one per collector.
VictoriaMetrics only collapses those into one when the storage side sets `-dedup.minScrapeInterval`; riptide has no setting of its own that de-duplicates on the write path.
Sharding collectors by discovery filter, as in [Discover exporters from NetBox](../guides/discovery-netbox.md#poll-devices-that-send-no-flows), avoids the overlap in the first place: each collector's filter names its own slice, and a dead collector's slice stays unpolled until it returns or an operator retags its devices onto a live one.

## Messages

`<name>` stands for a polling profile name; `<endpoint>` and `<address>` for a redacted SNMP endpoint or address; numeric placeholders stand for the value the message reports.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `riptide.metrics.remote-write.url is not a URL: <reason>` | `url` is set but not a URL | Fix the URL, or unset it to disable the sink |
| `riptide.metrics.remote-write.url must be an http or https URL (got scheme <scheme>)` | `url` is a URL with another scheme, such as `file` or `ftp` | Use the receiver's `http` or `https` write URL |
| `riptide.metrics.remote-write.batch.max-samples must be > 0 (got <n>)` | Zero or negative `batch.max-samples` | Use a positive value |
| `riptide.metrics.remote-write.batch.max-latency must be > 0 (got <duration>)` | Zero, negative or unset `batch.max-latency` | Use a positive duration |
| `riptide.metrics.remote-write.queue-capacity must be > 0 (got <n>)` | Zero or negative `queue-capacity` | Use a positive value |
| `riptide.metrics.remote-write.max-attempts must be > 0 (got <n>)` | Zero or negative `max-attempts` | Use a positive value |
| `riptide.metrics.remote-write.retry-backoff must be > 0 (got <duration>)` | Zero, negative or unset `retry-backoff` | Use a positive duration |
| `riptide.metrics.remote-write.shutdown-grace-period (<duration>) must be at least twice batch.max-latency (<duration>)` | `shutdown-grace-period` set too low for `batch.max-latency` | Raise `shutdown-grace-period`, or lower `batch.max-latency` |
| `riptide.snmp.polling.<name>: timeout <n> ms x <n> attempts exceeds the walk budget of <n> ms (80% of refresh-interval); lower the timeout or lengthen refresh-interval.` | `collect` is set and `timeout x (retries + 1)` does not fit in 80 percent of `refresh-interval` | Lower `timeout` or `retries`, or lengthen `refresh-interval` |
| `The inventory marks <n> entries poll: always, but riptide.snmp.poll.max-exporters is <n>. None of them is polled. Raise the limit or narrow the discovery filter.` | More `poll: always` entries than `max-exporters` allows; refused whole, not partially | Raise `riptide.snmp.poll.max-exporters`, or narrow the discovery filter that composes `poll: always` entries |
| `<n> of <n> poll: always entries are not polled. Registered exporters already fill riptide.snmp.poll.max-exporters (<n>). Raise the limit.` | The `poll: always` entries fit under `max-exporters`, but flow-registered exporters already hold the room; logged once per inventory sweep and counted on `snmp.poller.inventoryRefused` | Raise `riptide.snmp.poll.max-exporters` |
| `Exporter '<name>' (<address>) is poll: always, but no agent range with credentials covers it. It is not polled.` | A `poll: always` entry's address falls outside every agent range, or the covering range has no `credentials` | Add or fix an agent range covering the address |
| `SNMP endpoint <endpoint> answers ifTable but not ifXTable, so no octet series will exist for it` | The device implements ifTable but not the newer ifXTable | Informational; nothing to fix on riptide's side |
