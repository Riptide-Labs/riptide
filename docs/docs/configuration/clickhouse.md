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
riptide.clickhouse.username=writer_acme
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
| `flows_by_application_1m` | `application`, `protocol` |
| `flows_by_conversation_1m` | `srcAddr`, `dstAddr`, `application` |
| `flows_by_exporter_iface_1m` | `exporterAddr`, `exporterName`, `inputSnmp`, `outputSnmp` |
| `flows_by_geo_asn_1m` | `srcAs`, `dstAs`, `srcCountry`, `dstCountry` |

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

A batch is flushed at `max-rows` rows or after `max-latency`, whichever comes first. The defaults
follow ClickHouse's guidance of 10k–100k rows per insert at roughly one insert per second;
`max-latency` also bounds how stale dashboards go at low flow rates. When the queue is full —
ClickHouse cannot keep up — the collector **drops flows instead of blocking** (blocking would
backpressure the parsers into the network socket, where the loss is invisible); drops are counted
and logged with a rate limit. Alongside the `droppedRows` counter sit a queue-depth gauge, a
batch-size histogram, a flush timer (`persister.batch.flush`), and a `failedRows` counter for
batches whose insert failed.

:::warning[Error visibility under batching — watch the logs]

With batching enabled, **insert failures never surface to the ingest path**: a failed batch is
logged by the flusher and counted in `persister.batch.failedRows`, and ingestion continues. The
synchronous per-insert error signal (e.g. the `469 VIOLATED_CONSTRAINT` rejection in provisioned
mode) only exists with `batch.enabled=false` (and coalescing off).

Today the operator-facing signal is therefore the flusher's **`ERROR` log line** (`Failed to
persist a batch of N flows`) — alert on it. The `persister.batch.*` metrics live only in the
in-process registry: riptide currently ships **no metrics reporter or exporter** (the management
server serves `/livez` and `/readyz` only), so they cannot be scraped or graphed yet. They are
wired and ready for the day an exporter lands — a known gap, not a monitoring recommendation.

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
