---
title: Insert batching and dead letters
sidebar_position: 7
description: Why the collector batches inserts client-side, what a refused batch costs and where its rows go, how shutdown drains the queue, and why coalescing and compression default the way they do.
---

# Insert batching and dead letters

Every insert into ClickHouse forms a part and fires the four rollup materialized views, so many small inserts collapse throughput.
The collector therefore buffers flows in a bounded queue and has one background flusher issue one insert per batch.
The keys are on the [ClickHouse reference](../reference/clickhouse.md#settings); this page says why they are shaped that way and what happens when an insert fails.

## Who owns the schema

In manage mode (`manage-schema=true`) riptide ensures the schema idempotently at every start.
`flows`, the rollups and `flows_dead_letter` use `CREATE TABLE IF NOT EXISTS`, so an existing table is never replaced and its rows survive a restart.
The `samples` view uses `CREATE OR REPLACE VIEW` because a view holds no data: it is always refreshed and can never go stale.
Columns added by a later release are appended with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`; anything that is not an added column is not migrated, and the startup column check fails fast instead.

In validate mode (`manage-schema=false`) riptide creates nothing.
An admin owns the schema and RBAC, and each collector connects as a narrowly scoped writer that only inserts.
That is the multi-tenant posture, see [Multi-tenancy](multi-tenancy.md).

## Why batching {/* #insert-batching-batch */}

Dispatch is per flow record, and historically every record was its own insert.
Issue [#382](https://github.com/Riptide-Labs/riptide/issues/382)'s benchmark, framed at the packet level with about 24 flow records per received packet, capped a 4-vCPU lab VM at about 150 inserts/s, about 3,600 rows/s, with the CPU mostly idle.
Since #382 the collector batches client-side: a batch is flushed at `max-rows` rows or after `max-latency`, whichever comes first.
The defaults follow ClickHouse's guidance of 10k to 100k rows per insert at roughly one insert per second, and `max-latency` also bounds how stale dashboards go at low flow rates.

When the queue is full, ClickHouse cannot keep up, and the collector drops flows instead of blocking.
Blocking would backpressure the parsers into the network socket, where the loss is invisible; a dropped flow is counted on `persister.batch.droppedRows` and logged with a rate limit.
The other `persister.batch.*` series are in the [metrics reference](../reference/metrics.md).

The `logPersisting.persister` timer measures only the enqueue latency, the hand-off into the buffer, normally microseconds.
Insert duration lives in `persister.batch.flush`.

## What an operator sees when an insert fails

With batching enabled, insert failures never surface to the ingest path.
A failed batch is logged by the flusher and counted in `persister.batch.failedRows`, and ingestion continues.
The synchronous per-insert error, such as the `469 VIOLATED_CONSTRAINT` rejection in provisioned mode, only exists with `batch.enabled=false` and coalescing off.

The signals are the flusher's ERROR line, `Failed to persist a batch of N flows, flusher does not retry, some may be committed`, and the `persister.batch.*` metrics on the management server's [`/metrics` endpoint](../reference/management.md#metrics-endpoint).
Alert on a sustained `droppedRows` or `failedRows` rate and on `queueDepth` approaching `queue-capacity`.
Treat `failedRows` as a signal rather than a loss figure, for the reason below.
The [readiness contract](../reference/management.md#health-endpoints--probes) deliberately keeps ClickHouse out of `/readyz`, so these metrics are the whole story.

## A poison row costs a whole batch, and the batch is kept

Because rows are inserted together, one row the server rejects fails the entire insert: up to `max-rows` flows fail instead of the one bad flow the per-record path would have lost.
The flusher logs the batch size with the error, writes every row of the batch to `flows_dead_letter`, and moves on, so one bad batch never wedges ingestion.
The rows are inspectable and replayable by hand, see [Inspect and replay dead letters](../operations/dead-letters.md), but they are still not in `flows`.
A persistent source of rejected rows, a mis-tenanted collector against the multi-tenant `CHECK` barrier for example, still costs proportionally more live data.
Lowering `max-rows` limits the blast radius at the cost of throughput.
If the dead-letter write fails as well, because the deployment was provisioned before that table existed or the server has gone away, the rows are counted under `deadLetterFailedRows` and the outcome is the older behaviour exactly.

With `batch.enabled=false` there is no batch and no dead letter: the rejection reaches the caller synchronously and the records are counted in `pipeline.dispatchErrors`.

"A whole batch" is only true while the whole batch lands in one committed block.
ClickHouse cuts an incoming insert into blocks and may commit them separately.
When it does, a refused insert is not atomic: the blocks already accepted stay committed, part of the batch persists while the whole batch is counted as failed, and the rollups are left inconsistent with the base table.
`MultiBlockPoisonProbeIT` measures this on the pinned image and pins both a server that behaves this way and one that does not.

The counters say this too.
A refused batch increments `persister.batch.failedRows`, not `droppedRows`; the drop counter is for queue-full and post-shutdown loss.
It increments `deadLetteredRows` as well, and that is a second statement rather than a correction: the rows are kept in `flows_dead_letter`, and they are still not in `flows`.
`failedRows` charges the whole batch, so for a refused insert it is an upper bound on the loss: where a prefix did commit, those rows are both persisted and counted failed, and nothing reports the difference.
Riptide cannot tell the two apart, so the flusher's line admits the possibility instead of claiming the batch was dropped.

Riptide does not tell you whether your server is affected, and neither does this page.
Two attempts at a startup check that modelled the relevant server settings were each wrong in both directions, and two independent measurements of those settings produced contradictory rules.
A two-million-row insert against an untouched server committed nothing on refusal, so no simple "raise `max-rows` past N" statement survived contact with the data either.
The settings involved are `max_insert_block_size`, `min_insert_block_size_rows` and `min_insert_block_size_bytes`; how they combine is not something this project can state, see [#700](https://github.com/Riptide-Labs/riptide/issues/700).
What is safe to say: no partial write has been produced at stock server settings, so plan against the whole-batch loss model unless you have tuned those settings.
If you have, the loss model is not known to hold.

## Shutdown drains the queue

On shutdown the repository stops accepting new flows and drains everything already accepted, so accepted flows keep at-least-once delivery.

Budget the service manager's stop timeout for the whole shutdown sequence, not just the grace period.
The listeners stop first and sequentially, and each parser waits up to 5 s for its dispatch executor to finish in-flight flows.
That is per parser, not per receiver: a `multi` receiver runs one parser per enabled protocol and stops them one after another, so one `multi` receiver with all four protocols can take about 20 s on its own.
Only then does the batch drain's `shutdown-grace-period` start.

```text
worst case ≈ (5 s × total parsers across all receivers) + shutdown-grace-period + 1 s + 2 s (management server) + 2 s (MCP SSE transport, when enabled)
```

The 1 s is the grace-expired path only: a flusher that has to be interrupted gets one more second to unwind before the queue is swept, so the sweep and the client teardown never race an insert still in flight.
The management server and the MCP SSE server each get up to 2 s to close their connections.
A collector with one `multi` receiver (4 protocols) and one IPFIX receiver is therefore 5 × 5 + 5 + 1 + 2 = about 33 s, about 35 s with SSE enabled.
Keep that sum below systemd's `TimeoutStopSec` (default 90 s), or the process is killed mid-drain and the buffer is lost.
`shutdown-grace-period` must be at least twice `max-latency`, enforced at startup, because the flusher notices the stop signal only between flush windows.

## Why coalescing is off under batching

Server-side coalescing (`async_insert`, acknowledged on buffer append) was the earlier answer to the small-insert flood.
Measured on two cores: 206 inserts/s without the rollups, 56 with them, 607 with the rollups and coalescing.
Client-side batching supersedes it: the server receives one large insert per batch, which coalescing cannot improve on.
Coalescing also hides insert errors from the collector entirely.
The insert is acknowledged before the server evaluates it, so a row the server later rejects, a mis-tenanted row failing the `CHECK` barrier included, is dropped without any error anywhere.
With coalescing off a failed insert is at least visible: as a flusher error plus `persister.batch.failedRows` under batching, or as a synchronous exception with batching disabled.

`riptide.clickhouse.async-inserts` unset therefore derives:

| Batching | Derived value | Why |
| --- | --- | --- |
| Enabled (default) | Off | Superseded, and off keeps insert errors visible to the flusher |
| Disabled | Follows `manage-schema`: on in manage mode, off in provisioned mode | Manage mode has no write barrier and the transport is lossy UDP anyway; in provisioned mode the synchronous `469 VIOLATED_CONSTRAINT` rejection is part of the isolation contract. Without this fallback `batch.enabled=false` alone would land on the measured-slowest combination, 56 inserts/s |

Set the key explicitly to override either derivation.

## Why compression is on

`riptide.clickhouse.compress-requests` LZ4-compresses insert payloads and cuts the bytes on the wire severalfold on flow data, which is what you want across a WAN or where egress is metered.

It is not free.
Profiling the batch flusher put LZ4 at roughly a fifth of that thread's CPU (`ClickHouseLZ4OutputStream.write` plus the LZ4 compressor frames).
A collector on the same LAN as ClickHouse pushes only single-digit MB/s uncompressed at tens of thousands of rows per second, a rounding error on 1 GbE, so turning it off there trades bandwidth nobody is paying for against CPU on the one thread that serializes every batch:

```properties
riptide.clickhouse.compress-requests=false
```

Leave it on across a WAN, in cloud deployments where egress is billed, or whenever the collector and ClickHouse are not on the same network.

## Trade-offs

| Choice | Gained | Given up |
| --- | --- | --- |
| Drop on a full queue instead of blocking | Loss is counted where it happens | Flows are lost under sustained ClickHouse lag |
| One insert per batch | Throughput, see #382 | A poison row fails up to `max-rows` flows; the synchronous error signal |
| Dead-letter the refused batch | Rows are inspectable and replayable | A second table to police; `failedRows` still charges the batch |
| Never replay a dead letter automatically | The rollups cannot be inflated by a re-insert | An operator has to decide |
| Coalescing off under batching | Insert errors stay visible | Nothing measurable; batching already amortizes the insert |
