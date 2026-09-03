---
sidebar_position: 4
title: ClickHouse
---

# ClickHouse

Enriched flows are persisted to ClickHouse:

```properties
riptide.clickhouse.endpoint=http://localhost:8123
riptide.clickhouse.username=default
#riptide.clickhouse.password=vault://secret/riptide/clickhouse#password
riptide.clickhouse.database=riptide
riptide.clickhouse.manage-schema=true
#riptide.clickhouse.compress-requests=true   # false trades bandwidth for CPU on a LAN
riptide.clickhouse.batch.enabled=true
riptide.clickhouse.batch.max-rows=10000
riptide.clickhouse.batch.max-latency=2s
riptide.clickhouse.batch.queue-capacity=40000
riptide.clickhouse.batch.shutdown-grace-period=5s
#riptide.clickhouse.async-inserts=   # unset: derived — off under batching, see below
```

## Credentials

`riptide.clickhouse.username` and `riptide.clickhouse.password` are **secret references**,
resolved through the same SPI as SNMP credentials:

- a bare literal is used verbatim (plain fallback — existing literal configs keep working);
- a `scheme://…` reference is resolved from a secret store at startup: `env://VAR`,
  `file:///path` (optionally `#key` into a properties file), `vault://…`, `sops://…`.

Resolution happens once, when the ClickHouse client is built. An **unresolvable reference fails
startup** — a database credential that cannot resolve is fatal (unlike an SNMP one, which
degrades). Leave `password` unset for the default user's empty password; a blank value is not a
valid reference. Per-tenant writer credentials are sourced this way so no plaintext appears in
configuration:

```properties
riptide.clickhouse.username=writer_acme@riptide
riptide.clickhouse.password=vault://secret/riptide/clickhouse/acme#password
```

## Schema ownership

`riptide.clickhouse.manage-schema` (boolean, default `true`) selects who owns the schema:

The database name (`riptide.clickhouse.database`) must match `[A-Za-z0-9_-]+` — names with other
characters (dots, spaces, unicode) are rejected at startup in manage mode. This is stricter than
what ClickHouse accepts under backtick quoting; rename such a database or use validate mode.

- **`true` (default, single-tenant)** — riptide ensures the schema idempotently at startup:
  the database with `CREATE DATABASE IF NOT EXISTS` (so a fresh single-node install needs no
  manual DDL — the configured user needs `CREATE` rights), the `flows` table with
  `CREATE TABLE IF NOT EXISTS` (an existing table is not replaced, so its data survives), and
  the `samples` view with `CREATE OR REPLACE VIEW` (a view holds no data, so it is always
  refreshed and can never go stale), and the [1-minute rollups](#rollups) with
  `CREATE TABLE IF NOT EXISTS` / `CREATE MATERIALIZED VIEW IF NOT EXISTS`. A fresh install is
  created; a restart keeps the data — so **flow data now survives a Riptide restart**.

  :::warning[Hand-created `samples` views need re-creating]

  The `samples` definition evolves: the bucket split was corrected in #270 (older definitions
  under-report traffic by up to the flow's bucket count), and the view body was restructured in
  #346 (older definitions are 5–15× slower on every query). Manage-mode collectors heal on
  restart, but a `samples` view an admin created by hand — e.g. in a provisioned deployment —
  keeps the old definition until it is re-created from the current one.

  :::
- **`false` (provisioned / multi-tenant)** — the collector creates nothing. It validates that the
  `flows` table exists and carries every column it inserts and **fails startup with a clear,
  provisioning-pointing error** if it does not. Use this when an admin owns the schema and
  RBAC and each riptide process is a narrowly-scoped writer that only uses the table. On a fresh
  single-node server the admin-side
  [`onboard --create-schema`](../deploy/multi-tenancy.md#what-it-provisions) bootstraps the database,
  the `flows` table and the [rollups](#rollups) as part of provisioning; without the flag, `onboard`
  requires the schema to exist and fails loudly if it does not (replicated clusters pre-create the
  table admin-side).

In both modes, startup verifies the `flows` table is present and carries every column riptide
inserts (including the `tenant`/`organisation`/`zone`/`system` identity columns) by reading the
table's own schema — so the check works even for a narrowly-granted writer without server-catalog
access, and a stale or mis-provisioned schema fails fast rather than surfacing later as an opaque
insert error.

:::warning

Schema evolution is not migrated automatically. Because manage mode uses
`CREATE TABLE IF NOT EXISTS`, a schema change between Riptide versions is **not** applied to
an existing table — the startup column check fails fast and the operator must drop and let
Riptide recreate the table (or re-provision it) until schema migrations land.

:::

Enrichment results are denormalized into the flow row at write time — exporter address,
resolved interface data (`inputSnmpIfName`/`ifAlias`/`ifSpeed` and the `output…`
counterparts), hostnames, classification, and locality — so queries never need join-time
lookups.

## Rollups

Alongside `flows` sit four **1-minute rollups**: `SummingMergeTree` tables kept up to date by
materialized views on `flows`. A dashboard that asks "top applications over the last 30 days"
reads a few thousand pre-aggregated rows instead of scanning every flow.

| table | dimensions (beyond the shared preamble) |
|---|---|
| `flows_by_application_1m` | `application`, `protocol`, `samplingInterval`, `flowProtocol` |
| `flows_by_conversation_1m` | `srcAddr`, `dstAddr`, `application`, `samplingInterval`, `flowProtocol` |
| `flows_by_exporter_iface_1m` | `exporterAddr`, `exporterName`, `inputSnmp`, `outputSnmp`, `samplingInterval`, `flowProtocol` |
| `flows_by_geo_asn_1m` | `srcAs`, `dstAs`, `srcCountry`, `dstCountry`, `samplingInterval`, `flowProtocol` |

`samplingInterval` and `flowProtocol` are carried together so sampling-corrected volume stays answerable beyond the raw table's retention — see [Sampling-corrected volume](./receivers.md#sampling-corrected-volume-beyond-raw-retention). Neither is offered as something to group by. Rows aggregated before each was appended read `0` and `''` respectively, and because the two were appended in different releases those boundaries do not coincide.

`flowProtocol` is a `LowCardinality(String)` here, not the `Enum8` the raw `flows` table uses. An appended enum column reads back as its smallest-numbered member for every row that predates it, which for that enum is `NetflowV5` — a real protocol, indistinguishable from a genuine reading. A string reserves `''`, which no protocol name can collide with.

Every rollup carries the same preamble — `tenant`, `organisation`, `timestamp`, `zone` — and the
same measures: `bytes`, `packets`, `flowCount`, plus the directional split `bytesIn`/`bytesOut`
and `packetsIn`/`packetsOut`. The undirected totals sit alongside the split deliberately: a flow
with `direction = UNKNOWN` counts in neither `bytesIn` nor `bytesOut`, so a query that summed the
split would quietly lose it. Use `bytes` unless you specifically want one direction.

`timestamp` keeps the raw table's column name, truncated to the minute
(`toStartOfMinute`), so a time filter ports between raw and rollup unchanged:

```sql
-- the same WHERE works against either table
SELECT application, sum(bytes) AS bytes
FROM riptide.flows_by_application_1m
WHERE timestamp >= now() - INTERVAL 7 DAY
GROUP BY application ORDER BY bytes DESC LIMIT 10;
```

Each rollup `X` is fed by a materialized view named `X_mv`. Query the table, never the `_mv`.

### Rollups gain dimensions in place

:::warning[Provisioned deployments must re-run `onboard` after an upgrade that adds a dimension]
A collector in validate mode (`manage-schema: false`) issues no DDL, so it cannot repair its own rollups. Until `riptide onboard` is re-run, riptide reports all four rollups as not matching this version and **declines them at query time** — so every query spanning 60 minutes or more is answered from raw `flows` instead. A query reaching past what raw `flows` retains comes back short — but not silently: the answer says so (see below).

Then **restart the collector**. The decision about which rollups are usable is made once, at
startup — a schema does not change under a running collector — so a collector that declined
them keeps answering from raw `flows` until it is restarted, however complete the repair was.

Manage-mode deployments repair an added dimension themselves on the next start and need nothing for it. A missing measure depends on how the engine combines it: one that is summed is not repaired in either mode, because reading `0` for every pre-upgrade row would make a total spanning the upgrade quietly too small — see *What riptide will not do in place* below. A measure combined some other way, such as the sampling-provenance bitmask, is added in place in manage mode, because `0` there means "no information recorded" rather than a wrong total.
:::

:::warning[Rolling back to an earlier version is not supported]
Riptide does not support downgrading, and this page does not describe how to do it.

One thing is worth stating anyway, because getting it wrong is expensive: **do not drop the rollup
materialized views.** With the views in place an older riptide leaves them alone and keeps
aggregating correctly into columns it does not read. With them gone, it either stops feeding the
rollup entirely or creates a narrow view over the still-wide target, writing the reserved value into
a sorting-key column for the rollup's full 365-day retention.
:::


When a release adds a dimension to a rollup, riptide appends it to the existing table rather than leaving upgraded deployments on the old shape. Two statements per rollup, both metadata-only: one `ALTER` that adds the column and extends the sorting key, then `MODIFY QUERY` on the materialized view.

**No aggregation is interrupted.** `MODIFY QUERY` swaps the view's SELECT in place. Dropping and recreating the view would leave a window in which nothing is aggregated, and a materialized view does not backfill, so that window would be a permanent hole in the rollup — measured at 0.44% of flows at a modest ingest rate. Riptide does not take that path.

**Rows aggregated before the append read the column's type default** — `0` for a number, empty for a string — because a column joining the sorting key cannot be given a `DEFAULT`. That is the boundary: it marks exactly which rows predate the change, and a query spanning an upgrade can exclude them with a predicate rather than needing to remember a date.

Repair runs in manage mode at startup, and in `riptide onboard` for provisioned deployments. It is idempotent, so it runs on every start and does nothing after the first. Riptide logs a line naming the rollup and the key change when it actually repairs something, and stays silent otherwise.

**A measure is added in place when the engine does not sum it.** The sampling-provenance bitmask is `SimpleAggregateFunction(groupBitOr, …)`, so a historical row reading `0` means "no provenance recorded" rather than a total that is too small. Riptide adds it to the existing table in manage mode and in `onboard`, with no loss and no rebuild. The distinction is the aggregation, not the fact that it is a measure — a *summed* measure is the case below. A validate-mode collector plans no repairs, so there it is reported and the rollup declined until a manage-mode start or `onboard` adds it.

**What riptide will not do in place:**

- **Shrink a sorting key.** A dimension removed from a release is not removed from your table. The grain would change and existing rows would not be re-aggregated, so riptide reports it and leaves the rollup alone.
- **Repair a corrected aggregate.** If a release changes how a measure is computed, the rollup is [declined at query time](#rollup-shape-checks-at-startup) rather than repaired. Repairing would readmit rows computed the old way with nothing to distinguish them, which is worse than answering from raw `flows`.
- **Add a summed measure.** A summed measure reading `0` for historical rows makes a `SUM` spanning the upgrade quietly too small, with nothing in the data marking where. Dimensions have a boundary; summed measures do not. In manage mode and in `onboard`, riptide refuses the rollup and logs `Rollup X left as it is: measure [...] is missing`, naming the remedy. A validate-mode collector never plans repairs, so there it only reports the shape drift and declines the rollup. The remedy discards that rollup's aggregated history: drop the rollup's view and target table, then restart a manage-mode collector, or re-run `riptide onboard` for a provisioned deployment and restart the collector after it.


### Rollup shape checks at startup

Riptide compares every rollup against the shape the running version intends, in both schema modes, and reports what it finds. The check itself changes nothing. The in-place repair described above is a separate step that runs before it, in manage mode and in `onboard` only.

The check exists because `CREATE MATERIALIZED VIEW IF NOT EXISTS` does nothing against a view that already exists. A deployment that has started riptide once keeps its original rollup shape indefinitely, so a rollup gaining a dimension or a measure in a new release reaches a fresh install and not an upgraded one. Previously nothing failed and nothing logged.

Four messages are possible, and they mean different things.

**"does not match this version's schema"** — the rollup's columns, their types, its **sorting key**, or its view's SELECT differ from what this version emits.

The line ends by saying what **this start** did about that rollup, in one of these:

- *"Work on it was attempted on this start and did not succeed; the failure is logged above."* — a statement ran for it and threw. The failure is a separate line, earlier in the same start.
- *"A repair was planned for it on this start and ran, and it still differs."* — the repair succeeded and something else about the rollup is still not what this version emits.
- *"No repair was planned for it on this start."* — the planner saw it and planned nothing for it.
- *"No repair was planned for any rollup on this start."* — the catalog could not be read, so the planner saw nothing at all.
- *"No repair is attempted on any start, because this deployment does not manage the schema."* — validate mode.
- Nothing further — the repair was refused, and the `left as it is` line above already gave the reason.

None of them says what a **later** start will do. One start's outcome does not establish that, and the next start can differ: a catalog read that failed transiently succeeds the second time and repairs the rollup unattended.

Ingestion is unaffected: raw `flows` still receives every flow, and a rollup is a query-path optimisation, not a collection path. Long-range queries stop using that rollup and are answered from raw `flows` instead. The other rollups keep serving.

::::warning[The fallback is bounded by raw retention]
Raw `flows` is kept for 30 days by default; the rollups are kept for 365. Part of why the rollups exist is that long-range queries outlive the raw table's expiry. So a query that falls back and reaches further back than the raw retention comes back **incomplete**, not merely slower — it returns the rows that still exist and, until riptide learned to say so, said nothing about the rest.

:::note[A short answer now says it is short]
When the table that answered cannot reach back to the start of the range you asked for, the result carries one extra entry naming the table, the earliest data it holds, and how much of your range that covers:

```
coverage_warning: answered from riptide.flows, which holds data from
                  2026-08-19 09:14:02. This answer covers 10080 of the
                  43200 minutes you asked for, which is what this table
                  retains. The rest is not missing from your network.
```

**A fully covered answer is unchanged**, so the entry means something when it appears rather than being a banner to skip.

The coverage figure is read from the data, not from a retention setting. That matters in both directions: a deployment provisioned with `onboard --ttl-days 7` is told about 7 days rather than riptide's 30-day default, and a rollup that only began aggregating last Tuesday is honest about holding less than its 365-day TTL permits.

Two things can shorten an answer and both are reported: the table holding less than you asked for, and riptide's own cap of 43200 minutes on a single query. Ask for more than the cap and the note says so.

It reports a shortfall it can observe at the start of the range. It is not a completeness guarantee — a table with a gap in the middle of its range still answers with that gap, and nothing here detects it.
:::

Treat a drifted rollup as something to repair promptly rather than to live with, and until then keep queries against it inside the raw retention window.
::::

One drift has a cheaper remedy than the rest. If the message names the **sorting key** and says a column sits outside it, the rollup carries the right columns but in a shape ClickHouse cannot repair in place — an existing column cannot be added to a sorting key. That happens when the column was added by hand. Drop just that column and restart; riptide then adds it back in the same statement that extends the key:

```sql
ALTER TABLE riptide.flows_by_application_1m DROP COLUMN samplingInterval;
```

Otherwise repair is manual. Drop the rollup's view and its target table and let riptide recreate both on the next start in manage mode:

```sql
DROP VIEW IF EXISTS riptide.flows_by_application_1m_mv;
DROP TABLE IF EXISTS riptide.flows_by_application_1m;
```

This discards that rollup's aggregated history. The raw `flows` table is untouched, so queries stay correct throughout, but they read raw rows for the affected range until the rollup accumulates again — and a materialized view does not backfill, so the pre-existing history does not come back. Weigh that against how much of the rollup's retention window you actually query.

**"could not be verified"** — riptide could not read the rollup's view definition or its sorting key, so it has no opinion either way. The rollup stays in use, because a rollup that has not been checked is not a rollup known to be wrong. When the cause is a missing grant on the view, the message says so explicitly; when riptide asked the server and could not interpret the answer, it says only that the view is not visible.

**"cannot be reached"** — the rollup's target table itself is not visible, so it either does not exist or the connecting user has no grant on it. A query routed there would fail outright, so riptide declines it and answers from raw `flows`. The usual cause is a database onboarded without `--create-schema`.

**"has no materialized view writing to it"** — the target table is fine, but the view that should feed it does not exist, so nothing is being written and the rollup would answer long-range queries from a table nothing fills. That is worse than a fallback, because an empty answer looks like a real one, so riptide declines the rollup and answers from raw `flows`. In validate mode, re-run `riptide onboard --create-schema` to recreate it — the provisioner refuses without that flag. In manage mode this start either tried and failed, in which case the reason is logged just before this message, or did not try because it could not read the catalog.

Riptide tells this apart from a missing grant by asking the server: a trivial query against the view answers `UNKNOWN_TABLE` when it is absent and `ACCESS_DENIED` when it exists but is not granted. The query is only issued for a view riptide could not see and whose target it *can* read, so a healthy deployment issues none.

:::note[The probe is a denied query by design]
On a deployment whose writer has not been granted `SHOW TABLES` on a view, that probe is refused, so each affected rollup produces one `ACCESS_DENIED` entry in `system.query_log` per start. That is expected, not a fault: the writer is deliberately not granted `SELECT` on a view, because rows read through a view's name are not filtered by a row policy on its target. Granting `SELECT` to silence it would open a read path around the policy. Add the `SHOW TABLES` grant below instead, which removes the probe entirely.
:::

The usual cause of the "could not be verified" message is grants — specifically a view that exists but is not granted, which riptide now says explicitly. It also covers the case where riptide asked the server and could not make sense of the answer. Riptide's collector connects as the writer, and ClickHouse hides objects a role holds no grant on rather than refusing the query, so a view the writer cannot see reads as zero rows. `riptide onboard` grants `SHOW TABLES` on each `X_mv` to the database's write role; a database provisioned before that grant existed, or by hand, needs it added:

```sql
GRANT SHOW TABLES ON riptide.flows_by_application_1m_mv TO `flow_writer@riptide`;
-- and the same for flows_by_conversation_1m_mv, flows_by_exporter_iface_1m_mv, flows_by_geo_asn_1m_mv
```

The role name carries the database (see [Multi-tenancy](../deploy/multi-tenancy.md#object-names-carry-their-database)). On a deployment onboarded before that, it is the unqualified `flow_writer`.

`SHOW TABLES` and not `SELECT`, deliberately. The write role is shared by every per-tenant writer in its database, and a row policy attached to a rollup target does **not** apply when the same rows are read through the view's name — granting `SELECT` there would give each of those writers a read path around the policy. `SHOW TABLES` makes the view visible in `system.tables` for the shape check and grants no data access.

Re-running `riptide onboard` against an existing database does this for you and is safe: it adds grants and preserves data.

### Insert batching (`batch.*`)

Historically the collector inserted flows as they were dispatched — dispatch is per **flow
record** — and every insert also feeds the four rollup views: each insert forms a ClickHouse part
and fires all four materialized views, so many small inserts collapse throughput. Issue #382's
benchmark, framed at the packet level (~24 flow records per received packet on average), capped a
4-vCPU lab VM at ~150 inserts/s ≈ 3,600 rows/s with the CPU mostly idle. Since #382 the collector
batches client-side: flows are buffered in a bounded in-memory queue and a single background
flusher issues **one insert per batch**.

| property | default | meaning |
|---|---|---|
| `riptide.clickhouse.batch.enabled` | `true` | Off falls back to the per-record insert path. |
| `riptide.clickhouse.batch.max-rows` | `10000` | Flush when this many rows are buffered. |
| `riptide.clickhouse.batch.max-latency` | `2s` | Flush whatever is buffered after this long. |
| `riptide.clickhouse.batch.queue-capacity` | `40000` | Buffer bound; a full queue drops flows (counted). |
| `riptide.clickhouse.batch.shutdown-grace-period` | `5s` | How long `stop()` waits for the drain. |
| `riptide.clickhouse.compress-requests` | `true` | LZ4-compress insert payloads. See below. |

A batch is flushed at `max-rows` rows or after `max-latency`, whichever comes first. The defaults
follow ClickHouse's guidance of 10k–100k rows per insert at roughly one insert per second;
`max-latency` also bounds how stale dashboards go at low flow rates. When the queue is full —
ClickHouse cannot keep up — the collector **drops flows instead of blocking** (blocking would
backpressure the parsers into the network socket, where the loss is invisible); drops are counted
and logged with a rate limit. Alongside the `droppedRows` counter sit a queue-depth gauge, a
batch-size histogram, a flush timer (`persister.batch.flush`), and a `failedRows` counter for rows
the flusher could not deliver: a failed insert, an unexpected `Error` inside the flusher, or rows
still in its hands when it is interrupted or the shutdown grace period expires.

:::warning[Error visibility under batching — watch the logs]

With batching enabled, **insert failures never surface to the ingest path**: a failed batch is
logged by the flusher and counted in `persister.batch.failedRows`, and ingestion continues. The
synchronous per-insert error signal (e.g. the `469 VIOLATED_CONSTRAINT` rejection in provisioned
mode) only exists with `batch.enabled=false` (and coalescing off).

The operator-facing signals are the flusher's **`ERROR` log line** (`Failed to persist a batch of N flows, flusher does not retry, some may be committed`) and the **`persister.batch.*` metrics**, scrapeable from the management server's [`/metrics` endpoint](../deploy/operations.md#metrics-endpoint) in Prometheus format.
Alert on a sustained `persister.batch.droppedRows` or `persister.batch.failedRows` rate and on `persister.batch.queueDepth` approaching `queue-capacity` — `failedRows` as a signal rather than a loss figure, since it is an upper bound ([why](../deploy/operations.md#metrics-endpoint)); the [readiness contract](../deploy/operations.md#health-endpoints--probes) deliberately keeps ClickHouse out of `/readyz`, so these metrics are the whole story.

Note also that the pre-existing `logPersisting.persister` timer now measures only the **enqueue**
latency (the hand-off into the buffer, normally microseconds) rather than insert duration; the
insert duration lives in `persister.batch.flush`. Relevant when metrics do become exportable.

:::

**A poison row now costs a whole batch.** Because rows are inserted together, a single row the
server rejects fails the entire insert: up to `max-rows` flows are dropped instead of the one bad
flow the per-record path would have lost. The flusher logs the batch size with the error and
moves on (one bad batch never wedges ingestion), but a persistent source of rejected rows — a
mis-tenanted collector against the multi-tenant CHECK barrier, say — now costs proportionally
more data. Lowering `max-rows` limits the blast radius at the cost of throughput.

**And "a whole batch" is only true while the whole batch lands in one committed block.** ClickHouse cuts an incoming insert into blocks and may commit them separately. When it does, a refused insert is *not* atomic: the blocks already accepted stay committed, so part of the batch persists while the whole batch is counted as failed, and the rollups are left inconsistent with the base table. Measured on the pinned image by `MultiBlockPoisonProbeIT`, which pins both a server that behaves this way and one that does not.

Note the counter: a refused batch increments `persister.batch.failedRows`, not `persister.batch.droppedRows`.
The drop counter is for queue-full and post-shutdown loss and stays at zero for this failure.
`failedRows` charges the **whole** batch, so for a refused insert it is an **upper bound** on the loss rather than a count of it: where a prefix did commit, those rows are both persisted and counted failed, and nothing reports the difference.
riptide cannot tell the two apart, so the flusher's insert-failure line admits the possibility instead of claiming the batch was dropped.

**riptide does not tell you whether your server is affected, and neither does this page.** Two attempts at a startup check that modelled the relevant server settings were each wrong in both directions, and two independent measurements of those settings produced contradictory rules. A two-million-row insert against a completely untouched server committed nothing on refusal, so no simple "raise `max-rows` past N" statement survived contact with the data either. The settings involved are `max_insert_block_size`, `min_insert_block_size_rows` and `min_insert_block_size_bytes`; how they combine is not something we can currently state, and a wrong rule here would either cry wolf or miss the case. See [#700](https://github.com/Riptide-Labs/riptide/issues/700).

What is safe to say: no partial write has been produced at stock server settings, so the whole-batch loss model above is the one to plan against unless you have tuned those settings. If you have, the loss model is not known to hold.

On shutdown the repository stops accepting new flows and drains everything already accepted —
buffered flows are flushed before the repository stops, preserving at-least-once delivery for
accepted flows.

Budget the service manager's stop timeout for the **whole** shutdown sequence, not just the grace
period. The listeners stop first and sequentially, and each **parser** waits up to 5 s for its
dispatch executor to finish in-flight flows — note *per parser*, not per receiver: a `multi`
receiver runs one parser per enabled protocol and stops them one after another, so a single
`multi` receiver with all four protocols can take ~20 s on its own. Only then does the batch
drain's `shutdown-grace-period` start:

```
worst case ≈ (5 s × total parsers across all receivers) + shutdown-grace-period + 1 s
```

The trailing second is the grace-expired path only: if the flusher has to be interrupted, it is
given one more second to unwind before the queue is swept, so that the sweep and the client
teardown never race an insert that is still in flight. A collector with one `multi` receiver
(4 protocols) and one IPFIX receiver is therefore 5 × 5 + 5 + 1 = ~31 s. Keep that sum below systemd's `TimeoutStopSec` (default 90 s), or the process
is killed mid-drain and the buffer is lost. `shutdown-grace-period` must be at least twice
`max-latency` (enforced at startup), since the flusher notices the stop signal only between flush
windows.

### Insert coalescing (`async-inserts`)

Server-side coalescing (`async_insert`, acknowledged on buffer append) was the previous answer to
the small-insert flood — measured on two cores: 206 inserts/s without the rollups, 56 with
them, 607 with the rollups and coalescing. Client-side [batching](#insert-batching-batch) has
superseded it: the server receives one large insert per batch, which coalescing cannot improve
on. Coalescing also hides insert errors from the collector entirely: the insert is acknowledged
before the server evaluates it, so a row the server later rejects is dropped **without any error
anywhere** — including a mis-tenanted row failing the CHECK barrier. (With coalescing off, a
failed insert is at least visible: as a flusher error log plus `persister.batch.failedRows`
under batching, or as a synchronous exception with batching disabled.)

`riptide.clickhouse.async-inserts` unset therefore now derives as:

- **batching enabled (default) → off.** Superseded, and off keeps insert errors visible to the
  flusher.
- **batching disabled → follows `manage-schema`** (the pre-batching default): on in manage mode
  — no write barrier exists and flow transport is lossy UDP anyway — and off in provisioned
  mode, where the synchronous `469 VIOLATED_CONSTRAINT` rejection is part of the isolation
  contract. Without this fallback, `batch.enabled=false` alone would silently land on the
  measured-slowest combination (56 inserts/s).

Set the property explicitly to override either derivation.

### Retention

Rollups keep **365 days** by default, against the raw table's 30. That is the point of them: the
aggregates outlive the flows they came from, so long-range queries keep working after the raw
rows expire. Retention is set at creation time (`TTL timestamp + INTERVAL <n> DAY`) — in
provisioned mode via `onboard --ttl-days`, which applies to the raw table; adjust a rollup's TTL
with `ALTER TABLE … MODIFY TTL` if you need something different.

:::warning[Raw retention above 365 days inverts the invariant]

`--ttl-days` sets only the **raw** table's TTL — the rollups stay at 365 unless altered. A raw
retention above 365 therefore makes the rollups expire *before* the raw rows, silently defeating
"aggregates outlive the flows": long-range queries lose the oldest aggregates while the raw data
still exists. If you set `--ttl-days` above 365, raise the rollups to at least the same value:
`ALTER TABLE <db>.<rollup> MODIFY TTL timestamp + INTERVAL <n> DAY` for each rollup.

:::

:::warning[Materialized views do not backfill]

A rollup only covers traffic inserted **after** it was created. Adding the rollups to an existing
deployment does not populate them from historical `flows` rows — they start empty and fill from
that moment on. To backfill, `INSERT INTO … SELECT` from `flows` yourself, keeping the same
grouping the view uses.

**Name the target columns, and order the list to match your `SELECT`.** `INSERT INTO … SELECT`
written without a column list is **positional**: ClickHouse matches by ordinal, never by name. It
also requires an exact count — a `SELECT` with the wrong number of expressions is refused with
`NUMBER_OF_COLUMNS_DOESNT_MATCH`, so a positional insert never leaves a column unset.

Riptide keeps an upgraded rollup in the same physical column order as a freshly created one, adding
columns with `AFTER` rather than letting them land last, so a positional insert against a
riptide-managed target is correct. Name the columns anyway: nothing in the statement tells you
whether that held.

**Once you name them, the target's physical order stops mattering — but the order you write them in
must match your `SELECT`.** ClickHouse pairs the *n*th name with the *n*th expression, so a list
pasted in the server's order beside a `SELECT` written in some other order corrupts exactly as a
positional insert would.

Use the server only to check you have named them **all**. A rollup
[gains dimensions in place](#rollups-gain-dimensions-in-place), so any list written down on this
page goes stale the next time one is added:

```sql
-- the complete set of columns, so nothing is silently left out
SELECT arrayStringConcat(groupArray(name), ', ') AS columns
FROM (SELECT name, position FROM system.columns
      WHERE database = 'riptide' AND table = 'flows_by_application_1m' ORDER BY position);

-- then write both sides in one order, yours
INSERT INTO riptide.flows_by_application_1m
    (tenant, organisation, timestamp, zone, application, protocol, /* … */)
SELECT tenant, organisation, toStartOfMinute(timestamp), zone, application, protocol  /* … */
FROM riptide.flows
WHERE …
GROUP BY …;
```

Two failures are worth knowing, because they are not the ones you might expect:

- **A shift is usually loud, not silent.** `flowProtocol` is a `LowCardinality(String)` sitting
  between the dimensions and the `UInt64` measures, so a shift that reaches it fails the insert with
  `CANNOT_PARSE_TEXT` rather than writing anything. A shift is only silent where every column it
  crosses is numeric.
- **A shift does not hide itself.** Each column takes its neighbour's value, so a shifted
  `samplingInterval` holds some other column's number — not `0`. `WHERE samplingInterval > 0` still
  returns those rows.

The genuinely silent mistake is the opposite one: **omitting a column from the list.** An omitted
column takes its type default, so the rows land with `samplingInterval = 0`. They are still
identifiable — a materialized view cannot produce a row with a rate of `0` *and* a known protocol,
so that pair is the fingerprint of a hand-written backfill, as
[sampling-corrected volume](./receivers.md#sampling-corrected-volume-beyond-raw-retention) explains.
What they escape is `WHERE samplingInterval > 0`, which silently drops them from every corrected
total. Name every column the query above returns.

:::

## Query performance

The `samples(ival)` view expands each flow into per-bucket rows, attributing bytes and packets to
each bucket **proportionally to the time the flow spent in it** (assuming an even rate across the
flow's `deltaSwitched`…`lastSwitched` interval). Summing over buckets always returns the flow's
exact totals. This is the same `time-proportional` load scheme SiLK's `rwcount` defaults to; no
other ClickHouse flow store offers it — the usual alternative (bucketing each flow entirely into
its start minute) spikes long flows into single buckets. The precision has a query-time cost:
every query over `samples()` processes roughly *flows × buckets-per-flow* rows. Three habits keep
that cost small:

**Use the rollups when buckets are a minute or coarser.** A `samples(ival = 60)` query and a
[rollup](#rollups) query differ in attribution (proportional vs. start-minute) but usually not in
what a dashboard shows — and the rollup reads thousands of rows instead of exploding millions.
Reach for `samples()` when you need sub-minute buckets or exact proportional attribution.

**Bound the flow interval *and* the raw record time, and keep top-N mapping on raw keys.** Inside
the view the bucketed `timestamp` shadows the raw column, so neither `WHERE timestamp …` nor
bounds on `deltaSwitched`/`lastSwitched` can use the table's partition or primary index — the
interval columns are in neither key, so those bounds only filter rows before the explosion (still
worthwhile). The raw, indexed column stays addressable through the view as `flow.timestamp`:
bound it too, widened by the longest flow duration you expect, and the query prunes partitions
like a direct table read:

```sql
-- top-10 source AS as bps series, {from}/{to}/{ival} filled in by the caller
WITH top AS (
    SELECT srcAs FROM riptide.flows
    WHERE timestamp >= {from} AND timestamp <= {to}
      AND lastSwitched >= {from} AND deltaSwitched <= {to}
    GROUP BY srcAs ORDER BY sum(bytes) DESC LIMIT 10
)
SELECT time, if(asn IS NULL, 'Other', toString(asn)) AS series, b   -- labels: hundreds of rows
FROM (
    SELECT timestamp AS time,
           if(srcAs IN top, toNullable(srcAs), NULL) AS asn,        -- raw-key mapping: millions of rows
           sum(bytes) * 8 / {ival} AS b
    FROM riptide.samples(ival = {ival})
    WHERE timestamp >= {from} AND timestamp <= {to}
      AND lastSwitched >= {from} AND deltaSwitched <= {to}          -- pre-explosion row filter
      AND flow.timestamp >= {from} - INTERVAL 1 HOUR                -- partition/index pruning on the
      AND flow.timestamp <= {to} + INTERVAL 1 HOUR                  -- raw column, widened by max flow duration
    GROUP BY time, asn
) ORDER BY time;
```

`NULL` is the 'Other' sentinel because it lies outside the value space: unknown AS arrives as
`srcAs = 0`, which is a real series (often the largest) and must not be folded into 'Other'.

**Render labels after aggregating, not per row.** The inner `GROUP BY` above runs over the
exploded rows — millions of them — so group on raw binary columns (addresses, AS numbers,
tuples) and build display strings (`IPv6NumToString`, `concat`, hostname fallbacks) in the outer
select, which sees only a few hundred aggregated rows. For high-cardinality keys like
conversations this alone is worth ~1.5×; formatted-string group keys also force ClickHouse's
slowest aggregation path.

:::note

The bundled top-10 dashboards predate this guidance and still render their series labels per
exploded row; rewriting them to this pattern is follow-up work tracked in #346.

:::

The view needs a ClickHouse with the new query analyzer (24.8+ recommended, where it is the
default). The view does not emit zero rows for empty buckets — use
`ORDER BY time WITH FILL STEP {ival}` (or Grafana's null-as-zero option) when a panel needs
gap filling.

## Identity columns

Every persisted flow carries four identity columns stamped by the collecting process:
`tenant`, `organisation`, `zone` (the isolated network) and `system` (per-instance
provenance). They default so an out-of-the-box single-tenant deployment works unchanged —
`tenant`, `organisation` and `zone` default to `default`; `system` defaults to the process
host name (`riptide.identity.system` → `HOSTNAME` → `InetAddress.getHostName()` →
`default`). Configure them under `riptide.identity`:

```properties
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
riptide.identity.zone=dmz
riptide.identity.system=collector-01
```

The flows table sorts by `(tenant, organisation, toStartOfHour(timestamp), <flow tuple>)`;
`zone` and `system` are filter dimensions and stay out of the sort key. Partitioning stays
time-only (`toYYYYMMDD(timestamp)`). For the full identity model and how `tenant`/`organisation`
anchor hard isolation, see the [Multi-tenancy runbook](../deploy/multi-tenancy.md#the-identity-model).

:::note

`zone` replaces the former `riptide.location` key. `riptide.location` is deprecated but
still accepted for one release (mapped to `zone` with a warning); prefer
`riptide.identity.zone`.

:::

## Write isolation (multi-tenant)

In provisioned mode (`manage-schema=false`) an admin owns the schema and RBAC, and each riptide
process connects as a **narrowly-scoped writer** that can only write its own tenant's rows. The
barrier is enforced entirely by ClickHouse — **riptide never emits a `CHECK` constraint** (that
would break single-tenant manage mode, which has no `SQL_tenant` setting); it only stamps its
configured `tenant`/`organisation` (see [Identity columns](#identity-columns)) and inserts.

The mechanism is a per-row `CHECK` constraint that ties each row's `tenant`/`organisation` to a
`CONST` custom setting pinned on the writer credential. A collector whose config is tampered to
claim another tenant still carries its own credential, so the server rejects the mismatched row.

### Server requirement

Custom settings with the `SQL_` prefix must be enabled. This is **server config, not an env
var** — add a `config.d` snippet:

```xml
<!-- /etc/clickhouse-server/config.d/custom-settings.xml -->
<clickhouse>
    <custom_settings_prefixes>SQL_</custom_settings_prefixes>
</clickhouse>
```

### Provisioning

The `flows` table is created by a riptide manage-mode start (or equivalent DDL); the barrier
constraints are added by `ALTER` (evaluated only on `INSERT`, so no `SQL_tenant` need be defined at
DDL time):

```sql
ALTER TABLE riptide.flows ADD CONSTRAINT tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT org_pinned    CHECK organisation = getSetting('SQL_org');
```

The per-tenant writer/reader users, role grants, quota, and row policy are provisioned by the
[`onboard` subcommand](../deploy/multi-tenancy.md#onboard-a-tenant) — one command per
`(tenant, org)` that also prints the collector's config stanza. See the
[Multi-tenancy runbook](../deploy/multi-tenancy.md) for the full recipe.

### What the barrier guarantees

- **Honest write persists** — riptide's stamped `tenant`/`organisation` match the credential's
  `CONST` settings, so rows are stored.
- **Cross-tenant write is rejected** — a tampered config stamping another tenant fails the
  constraint: `Code: 469 … (VIOLATED_CONSTRAINT)`.
- **The pin cannot be lifted** — any attempt to override the `CONST` setting (a `SET` or a
  query-level `SETTINGS SQL_tenant=…`) fails: `Code: 452 … (SETTING_CONSTRAINT_VIOLATION)`.
- **Reads stay isolated** — the row policy limits each reader to its own tenant's rows.

:::note

This section covers the **write** provisioning. For the read-side hardening (per-tenant BI
users + row policies), Grafana topology, the end-to-end onboarding recipe, and the scaling
ceiling, see the [Multi-tenancy runbook](../deploy/multi-tenancy.md).

:::

## Insert compression

`riptide.clickhouse.compress-requests` (boolean, default `true`) LZ4-compresses insert
payloads on the way to ClickHouse. It cuts the bytes on the wire severalfold on flow data,
which is what you want across a WAN or where egress is metered.

It is not free. Profiling the batch flusher put LZ4 at roughly a fifth of that thread's CPU
(`ClickHouseLZ4OutputStream.write` plus the LZ4 compressor frames). A collector sitting on
the same LAN as ClickHouse pushes only single-digit MB/s uncompressed at tens of thousands
of rows per second — a rounding error on 1 GbE — so turning it off there trades bandwidth
nobody is paying for against CPU on the one thread that serializes every batch:

```properties
riptide.clickhouse.compress-requests=false
```

Leave it on across a WAN, in cloud deployments where egress is billed, or whenever the
collector and ClickHouse are not on the same network. Response compression is always on and
is unaffected — it only covers the schema queries at startup.
