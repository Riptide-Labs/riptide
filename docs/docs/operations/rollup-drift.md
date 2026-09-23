---
title: Recover from a rollup shape message
sidebar_position: 2
description: What each "Rollup …" startup line means, which of them takes a rollup out of the query path, and the remedy for each, from a single DROP COLUMN to a SHOW TABLES grant to a drop-and-recreate.
---

# Recover from a rollup shape message

- **Severity:** ticket. Ingestion is unaffected; long-range queries are answered from raw `flows` until the rollup is repaired, and an answer that reaches past the raw retention says so.
- **Alert:** a `WARN` line starting `Rollup ` at startup.

## Symptom

At startup riptide compares every rollup against the shape this version emits and logs one line per rollup it will not use as it stands.
Queries spanning 60 minutes or more that used that rollup are answered from raw `flows` instead, and an answer reaching past what `flows` retains carries a `coverage_warning` entry, see [Rollups](../architecture/rollups.md#the-fallback-is-bounded-by-raw-retention).

## Check

```bash
grep 'Rollup ' riptide.log
```

Healthy output:

```text
```

A healthy start prints no `Rollup ` line at all: the repair step is silent when it has nothing to do, and the check is silent when every rollup matches.

## Diagnose

| Line | Cause | Rollup in use? | Go to |
| --- | --- | --- | --- |
| `Rollup X does not match this version's schema …` ending `No repair is attempted on any start, because this deployment does not manage the schema.` | Validate mode; a release added a dimension or measure and `onboard` has not been re-run | No | [Fix: re-run onboard](#fix-re-run-onboard) |
| `… does not match …` and the message names the **sorting key** with a column outside it | The column was added by hand, so it exists but cannot be added to the key in place | No | [Fix: drop the hand-added column](#fix-drop-the-hand-added-column) |
| `… does not match …` ending `Work on it was attempted on this start and did not succeed; the failure is logged above.` | A repair statement threw; the failure is a separate line earlier in the same start | No | Read that line; then [Fix: drop and recreate](#fix-drop-and-recreate) if it does not resolve |
| `… does not match …` ending `A repair was planned for it on this start and ran, and it still differs.` | The repair succeeded and something else about the rollup differs | No | [Fix: drop and recreate](#fix-drop-and-recreate) |
| `… does not match …` ending `No repair was planned for it on this start.` or `No repair was planned for any rollup on this start.` | The planner saw the rollup and planned nothing, or the catalog could not be read at all | No | Restart once; a transient catalog read failure resolves on the next start. Otherwise [Fix: drop and recreate](#fix-drop-and-recreate) |
| `Rollup X left as it is: measure [...] is missing …` | A release added a summed measure, which riptide refuses to add in place | No | [Fix: drop and recreate](#fix-drop-and-recreate) |
| `Rollup X could not be verified: …` | Riptide could not read the view definition or the sorting key, usually a missing `SHOW TABLES` grant on the `_mv` | Yes | [Fix: grant SHOW TABLES](#fix-grant-show-tables) |
| `Rollup X cannot be reached: …` | The target table is not visible: it does not exist or the connecting user holds no grant on it, usually a database onboarded without `--create-schema` | No | [Fix: re-run onboard](#fix-re-run-onboard) with `--create-schema` |
| `Rollup X has no materialized view writing to it: …` | The target exists but nothing feeds it | No | Validate mode: [Fix: re-run onboard](#fix-re-run-onboard) with `--create-schema`. Manage mode: this start either tried and failed, with the reason logged just before, or could not read the catalog |

None of these lines says what a later start will do.
One start's outcome does not establish it, and the next start can differ: a catalog read that failed transiently succeeds the second time and repairs the rollup unattended.

Riptide tells "has no materialized view" apart from a missing grant by asking the server: a trivial query against the view answers `UNKNOWN_TABLE` when it is absent and `ACCESS_DENIED` when it exists but is not granted.
The query is only issued for a view riptide could not see and whose target it can read, so a healthy deployment issues none.
On a deployment whose writer lacks `SHOW TABLES` on a view, that probe is refused, so each affected rollup produces one `ACCESS_DENIED` entry in `system.query_log` per start.
That is expected: the writer is deliberately not granted `SELECT` on a view, because rows read through a view's name are not filtered by a row policy on its target.
Add the `SHOW TABLES` grant below instead, which removes the probe entirely.

## Fix: re-run onboard

Only a manage-mode collector or `riptide onboard` issues DDL.

1. Re-run `onboard` for the database, adding `--create-schema` when the line says `cannot be reached` or `has no materialized view`. The run is idempotent, adds grants and preserves data. The flags are on the [provisioning CLI reference](../reference/provisioning-cli.md).
2. Restart the collector. Which rollups are usable is decided once, at startup, so a collector that declined them keeps answering from raw `flows` until it restarts, however complete the repair was.
3. Run the check above.

## Fix: drop the hand-added column

An existing column cannot be added to a sorting key, so a column added by hand leaves the rollup with the right columns in a shape ClickHouse cannot repair in place.
Drop just that column and restart; riptide adds it back in the same statement that extends the key.

1. Drop the column the message names.

   ```sql
   ALTER TABLE riptide.flows_by_application_1m DROP COLUMN samplingInterval;
   ```

2. Restart a manage-mode collector, or re-run `riptide onboard` and then restart the collector.
3. Run the check above.

## Fix: grant SHOW TABLES

Riptide's collector connects as the writer, and ClickHouse hides objects a role holds no grant on rather than refusing the query, so a view the writer cannot see reads as zero rows.
`riptide onboard` grants `SHOW TABLES` on each `X_mv` to the database's write role; a database provisioned before that grant existed, or by hand, needs it added.

1. Grant visibility on each view, `SHOW TABLES` and not `SELECT`.

   ```sql
   GRANT SHOW TABLES ON riptide.flows_by_application_1m_mv TO `flow_writer@riptide`;
   -- and the same for flows_by_conversation_1m_mv, flows_by_exporter_iface_1m_mv, flows_by_geo_asn_1m_mv
   ```

   The role name carries the database, see [Multi-tenancy](../architecture/multi-tenancy.md#object-names-carry-their-database); on a deployment onboarded before that, it is the unqualified `flow_writer`.
   Re-running `riptide onboard` does this for you.

2. Restart the collector and run the check above.

`SELECT` would give every per-tenant writer in the database a read path around the row policy: a policy attached to a rollup target does not apply when the same rows are read through the view's name.
`SHOW TABLES` makes the view visible in `system.tables` for the shape check and grants no data access.

## Fix: drop and recreate

:::warning[This discards that rollup's aggregated history]

The raw `flows` table is untouched, so queries stay correct throughout, but they read raw rows for the affected range until the rollup accumulates again.
A materialized view does not backfill, so the pre-existing history does not come back unless you [backfill it](../guides/backfill-a-rollup.md) from rows `flows` still holds.
Weigh that against how much of the rollup's retention window you actually query.

:::

1. Drop the rollup's view and its target table.

   ```sql
   DROP VIEW IF EXISTS riptide.flows_by_application_1m_mv;
   DROP TABLE IF EXISTS riptide.flows_by_application_1m;
   ```

2. Restart a manage-mode collector, which recreates both on the next start, or re-run `riptide onboard --create-schema` and restart the collector.
3. Run the check above.

## Escalate

Open an issue on [Riptide-Labs/riptide](https://github.com/Riptide-Labs/riptide/issues) with the complete `Rollup ` lines from one start, the output of `SELECT name, sorting_key, engine_full FROM system.tables WHERE database = 'riptide' AND name LIKE 'flows_by_%'`, and the riptide version.

## Signals

| Signal | Where | Healthy |
| --- | --- | --- |
| `WARN … Rollup …` | riptide's log at startup | Absent |
| `coverage_warning` | An MCP tool answer | Absent for ranges inside the answering table's retention |
| `ACCESS_DENIED` rows for `flows_by_*_1m_mv` | `system.query_log` | Absent once `SHOW TABLES` is granted |

## Open questions

- The `DROP COLUMN`, `DROP VIEW` and `DROP TABLE` statements were not run while writing this page. The healthy check output was captured from a 0.15.0 start against ClickHouse 26.7 in manage mode.
