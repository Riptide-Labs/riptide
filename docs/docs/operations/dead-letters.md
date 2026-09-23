---
title: Inspect and replay dead letters
sidebar_position: 3
description: What a non-zero deadLetteredRows or deadLetterFailedRows means, how to read the refused batches out of flows_dead_letter, and why replaying them is an operator's decision that riptide never makes.
---

# Inspect and replay dead letters

- **Alert:** a sustained `persister_batch_failedRows` rate, or any `persister_batch_deadLetterFailedRows` movement

## Symptom

A refused insert no longer discards its rows, as long as batching is on (`riptide.clickhouse.batch.enabled`, the default).
The flusher writes every row of the refused batch to **`flows_dead_letter`** and counts them under `persister.batch.deadLetteredRows`.
If that write fails too, the rows are counted under `persister.batch.deadLetterFailedRows` and the behaviour is exactly what it was before the table existed.
`persister.batch.failedRows` still charges the whole batch either way, because a dead-lettered row is still not in `flows`.
On a refused batch, `failedRows − deadLetteredRows` is what riptide no longer has anywhere.

With batching off there is no flusher, no batch and no dead letter: the rejection reaches the caller synchronously and the records are counted in `pipeline.dispatchErrors` instead.
That path inserts one call at a time, so a poison row costs that call rather than up to `max-rows` flows.

## Check

```bash
curl -s http://localhost:8080/metrics | grep -E '^persister_batch_(failedRows|deadLetteredRows|deadLetterFailedRows)'
```

Healthy output:

```text
persister_batch_deadLetterFailedRows 0.0
persister_batch_deadLetteredRows 0.0
persister_batch_failedRows 0.0
```

## Diagnose

| Finding | Likely cause | Go to |
| --- | --- | --- |
| `deadLetteredRows` moves with `failedRows` | a reachable server refused a batch: a poison row, a constraint violation, a quota | [Read the refused batches](#read-the-refused-batches) |
| `deadLetterFailedRows` moves | the deployment was provisioned before the table existed, or the server has gone away | [Add the table](#add-the-dead-letter-table) |
| `failedRows` moves, neither dead-letter counter does, and the log names a connection failure | a severed transport; the dead-letter write goes to the same server over the same client, so it fails with the insert | the rows are gone; see [Where flows can be lost](../architecture/loss-accounting.md) |

## Read the refused batches

```sql
SELECT tenant, failedAt, error, count() AS rows
FROM riptide.flows_dead_letter
GROUP BY tenant, failedAt, error
ORDER BY failedAt DESC;
```

Expected output on a collector that has refused nothing:

```text
```

One batch's rows, as JSON:

```sql
SELECT payload FROM riptide.flows_dead_letter WHERE failedAt = '...' AND tenant = '...';
```

The table's columns are `tenant`, `failedAt`, `error` and `payload`.
It carries the same tenant row policy as `flows`, so a tenant reads only its own dead letters, and its `SELECT` is granted per user rather than to the shared reader role; see [Multi-tenancy](../architecture/multi-tenancy.md).
It carries no `CHECK` constraint, which is the point: its job is to accept rows `flows` refused, and the commonest refusal is that constraint firing.
One consequence follows: a writer whose config lies about its tenant has its write refused, and the dead letter it files is then visible to the tenant it named.
The write itself is still refused; what changes is that the attempt leaves evidence.

## Decide whether to replay

A dead letter is replayed by an operator, deliberately, and never by riptide.
There is no automatic re-insert and there is no flag to turn one on.
The reason is the rollups: they are `SummingMergeTree` targets fed by materialized views on `flows`, and their retention deliberately outlives the raw table's, so a row re-inserted into `flows` is summed into aggregates that survive the raw rows needed to diagnose the inflation.
A refused insert is also not always atomic, so riptide cannot tell which rows of the batch the server already kept; see [Insert batching and dead letters](../architecture/persistence.md).
Read the dead letters, decide, and insert what you mean to insert.

## Add the dead-letter table

A deployment provisioned before the table existed keeps collecting, and a refused batch costs what it always cost until the table arrives.
Re-run `riptide onboard --create-schema`, which adds the one table and nothing else; see [Onboard a tenant](../guides/onboard-a-tenant.md#adding-the-dead-letter-table-to-an-existing-deployment).
Then restart the collector.
Once the server has answered that the table is not there, riptide stops asking, because an un-migrated deployment would otherwise spend a round trip per refused batch to be told the same thing, and it reports that once, naming the remedy.
Like the rollups, the posture is decided while the process runs and re-read at startup.

## Escalate

Attach the grouped query's output and the flusher's `ERROR` line (`Failed to persist a batch of N flows, flusher does not retry, some may be committed`) to the issue.

## Signals

| Signal | Where | Healthy range |
| --- | --- | --- |
| **`persister.batch.failedRows`** | `/metrics` | no sustained rate |
| **`persister.batch.deadLetteredRows`** | `/metrics` | 0 |
| **`persister.batch.deadLetterFailedRows`** | `/metrics` | 0; any movement means refused rows are not being kept |

## Open questions

- The grouped query was run against the local ClickHouse 26.7.3.19, where `flows_dead_letter` holds zero rows, so the populated shape of the output was not captured.
