---
title: ClickHouse reference
sidebar_position: 10
description: Every riptide.clickhouse.* and riptide.identity.* key, the tested server versions, the two schema modes, the rollup and dead-letter tables, retention defaults and every message the persistence path can raise.
---

# ClickHouse reference

Enriched flows are persisted to ClickHouse.
How the batching path, the rollups and the dead-letter table behave is on [Insert batching and dead letters](../architecture/persistence.md) and [Rollups](../architecture/rollups.md).

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.clickhouse.endpoint`** | URL | `http://localhost:8123` | HTTP endpoint of the server. |
| **`riptide.clickhouse.username`** | secret reference | unset | User the collector connects as. Unset means the ClickHouse `default` user. See [Credentials](#credentials). |
| **`riptide.clickhouse.password`** | secret reference | unset | Unset means an empty password. A blank value is refused: `Secret reference must not be blank`. |
| **`riptide.clickhouse.database`** | string | `riptide` | Database holding `flows`. In manage mode the name must match `[A-Za-z0-9_-]+`; any other name is rejected at startup. |
| **`riptide.clickhouse.manage-schema`** | boolean | `true` | `true`: riptide creates and repairs the schema. `false`: riptide creates nothing and validates. See [Schema modes](#schema-ownership). |
| **`riptide.clickhouse.startup-wait`** | duration | `30s` | How long startup waits for a server that is not answering yet. `0` means one probe and no retry. Negative is rejected at construction. See [Startup wait](#startup-wait). |
| **`riptide.clickhouse.compress-requests`** | boolean | `true` | LZ4-compress insert payloads. Response compression is always on and covers only the schema queries at startup. |
| **`riptide.clickhouse.async-inserts`** | boolean | unset | Server-side insert coalescing. Unset derives: off while batching is enabled; with batching disabled it follows `manage-schema` (on in manage mode, off in provisioned mode). Set it to pin a value. |
| **`riptide.clickhouse.batch.enabled`** | boolean | `true` | Client-side insert batching. `false` falls back to one insert per flow record. |
| **`riptide.clickhouse.batch.max-rows`** | int | `10000` | Flush when this many rows are buffered. Must be greater than 0. |
| **`riptide.clickhouse.batch.max-latency`** | duration | `2s` | Flush whatever is buffered after this long. Must be positive. |
| **`riptide.clickhouse.batch.queue-capacity`** | int | `40000` | Buffer bound. A full queue drops flows, counted on `persister.batch.droppedRows`. Must be greater than 0. |
| **`riptide.clickhouse.batch.shutdown-grace-period`** | duration | `5s` | How long `stop()` waits for the flusher to drain. Must be at least twice `max-latency`; startup fails otherwise. |
| **`riptide.identity.tenant`** | string | `default` | Stamped into the `tenant` column of every row. |
| **`riptide.identity.organisation`** | string | `default` | Stamped into `organisation`. |
| **`riptide.identity.zone`** | string | `default` | Stamped into `zone`. |
| **`riptide.identity.system`** | string | host name | Stamped into `system`. Resolves from this key, then the `HOSTNAME` environment variable, then the JVM's host name, then `default`. Never fails startup. |
| **`riptide.location`** | string | unset | Deprecated. Still accepted and mapped to `zone` with a startup warning when `riptide.identity.zone` is unset. |

```properties
riptide.clickhouse.endpoint=http://localhost:8123
riptide.clickhouse.username=default
riptide.clickhouse.database=riptide
riptide.clickhouse.manage-schema=true
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
riptide.identity.zone=dmz
riptide.identity.system=collector-01
```

## Credentials

`riptide.clickhouse.username` and `riptide.clickhouse.password` are resolved through the same [secret reference](secret-references.md) resolvers as SNMP credentials.

| Value | Resolved as |
| --- | --- |
| A bare literal | Used verbatim. |
| `env://VAR`, `file:///path`, `file:///path#key`, `vault://…`, `sops://…` | Read from the secret store once, when the ClickHouse client is built. |
| A reference that does not resolve | Fails startup. A database credential is fatal, unlike an SNMP credential, which degrades. |

A per-tenant writer is configured this way, so no plaintext appears in configuration:

```properties
riptide.clickhouse.username=writer_acme@riptide
riptide.clickhouse.password=vault://secret/riptide/clickhouse/acme#password
```

The username carries the database because ClickHouse users are instance-wide.
`riptide onboard` prints the exact value to paste.
On a deployment onboarded before that rename the name is the unqualified `writer_acme`, see [Multi-tenancy](../architecture/multi-tenancy.md#object-names-carry-their-database).
This field is not a URL, so the `@` is written literally.
Inside a URL the same name must be written `%40`.

## Server versions {/* #server-versions */}

| Version | Status |
| --- | --- |
| ClickHouse 26.7 | Pinned by digest in the [compose stack](../guides/docker-compose.md); every integration test runs against it. |
| ClickHouse 26.8 | Verified by hand in two runs: a full-access user in manage mode (schema creation, column check, POJO registration, read-back insert), then a writer granted only `SELECT, INSERT` on its own `flows` table in validate mode (column check, registration, read-back insert). |
| Any other version | Untested. Not known to be broken. |

## Startup wait {/* #startup-wait */}

| Fact | Value |
| --- | --- |
| Window | `riptide.clickhouse.startup-wait`, default 30 s |
| Probe interval | 2 s |
| Per-probe bound | 5 s to connect, then 5 s for the answer, so the wait ends at most one probe past the window |
| Retried | Silence only: a refused connection, a host that does not resolve, a probe that times out |
| Not retried | Any answer, including an error such as a wrong password or a missing table; startup then fails with the [schema diagnostics](#messages) at once |
| Log lines | One WARN per unanswered probe from `StartupWait`, naming the endpoint, the attempt, the elapsed time and the cause; one INFO when the server answers after a wait |
| Client noise | The ClickHouse client logs each failed connect at WARN with a stack trace under `com.clickhouse.client.api`; lower that logger to `ERROR` if the traces are unwelcome |
| Effect on readiness | Receivers start after the wait, so `/readyz` answers `503` for as long as it lasts. The default sits under the compose healthcheck (`start_period` 20 s plus 3 × 10 s) and the documented Kubernetes `startupProbe` (30 × 2 s), see [Management endpoints](management.md). Raise the wait and raise those with it. |
| Read by | `ClickhouseRepository.start()`; `ClickhouseStartupWaitIT` sets a 1 s window against a closed port and observes the failure at that bound |

Against a port nothing listens on, with `--riptide.clickhouse.startup-wait=5s`:

```bash
java -jar riptide.jar --riptide.clickhouse.endpoint=http://127.0.0.1:18999 --riptide.clickhouse.startup-wait=5s
```

Expected output:

```text
WARN  o.r.repository.clickhouse.StartupWait : ClickHouse at http://127.0.0.1:18999 did not answer (attempt 1, PT0.036S elapsed of PT5S): org.apache.hc.client5.http.HttpHostConnectException: Connect to http://127.0.0.1:18999 failed: Connection refused (connect failed)
WARN  o.r.repository.clickhouse.StartupWait : ClickHouse at http://127.0.0.1:18999 did not answer (attempt 2, PT2.046S elapsed of PT5S): ...
WARN  o.r.repository.clickhouse.StartupWait : ClickHouse at http://127.0.0.1:18999 did not answer (attempt 3, PT4.061S elapsed of PT5S): ...
ERROR o.s.boot.SpringApplication : Application run failed
java.lang.IllegalStateException: ClickHouse at http://127.0.0.1:18999 did not answer within PT5S (4 attempt(s); last cause: org.apache.hc.client5.http.HttpHostConnectException: Connect to http://127.0.0.1:18999 failed: Connection refused (connect failed)). Start it, check riptide.clickhouse.endpoint, or raise riptide.clickhouse.startup-wait.
```

The 30 s default was measured, not guessed, on the containerlab topology from [#833](https://github.com/Riptide-Labs/riptide/issues/833): with the collector started first, ClickHouse 26.7 answered `/ping` 5.0 s after its container started, and the collector's fourth probe at 6.1 s was the first one answered.
A slower disk, a large catalog or an image still being pulled all make that longer, which is why the window is 30 s and not 10.
Without the wait, the same lab showed the collector exiting after its first statement and a restart policy turning that into a loop.
The [compose stack](../guides/docker-compose.md) never enters the wait: it starts `riptide` only after `clickhouse` is healthy.

## Schema modes {/* #schema-ownership */}

| `manage-schema` | Riptide creates | Riptide validates | Use when |
| --- | --- | --- | --- |
| **`true`** (default) | The database (`CREATE DATABASE IF NOT EXISTS`), `flows` (`CREATE TABLE IF NOT EXISTS`), the `samples` view (`CREATE OR REPLACE VIEW`), the four rollups and their views (`IF NOT EXISTS`), `flows_dead_letter` (`CREATE TABLE IF NOT EXISTS`). Columns riptide added since the table was created are appended with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`. Rollup dimensions are appended in place, see [Rollups](../architecture/rollups.md). | That `flows` carries every column riptide inserts | A single-tenant install. The configured user needs `CREATE` rights. |
| **`false`** | Nothing | That `flows` exists and carries every column riptide inserts, including `tenant`, `organisation`, `zone` and `system`. Startup fails with a provisioning-pointing error if it does not. `flows_dead_letter` is not demanded: a deployment provisioned before that table existed keeps collecting. | An admin owns the schema and RBAC and each riptide process is a narrowly scoped writer. `riptide onboard --create-schema` bootstraps the database, `flows`, the dead-letter table and the rollups, see [Onboard a tenant](../operations/tenants/onboard-a-tenant.md). Without the flag `onboard` requires the schema to exist. |

In both modes the column check reads `system.columns`.
A user granted nothing but `SELECT, INSERT ON <database>.flows` sees exactly that table's columns and needs no grant on `system`: ClickHouse filters `system.columns` by access rather than refusing the query, and `columns` stays readable with `select_from_system_db_requires_grant` turned on (measured on 26.7 and 26.8, where the same user is refused `system.parts` with `ACCESS_DENIED`).

Columns riptide does not insert:

| Column kind | Effect |
| --- | --- |
| `MATERIALIZED`, `ALIAS` | Fine. The server keeps computing them. |
| Plain `DEFAULT` | The insert fails with `No serializer found for column '…'`. The limit is the ClickHouse client's, not riptide's schema check. |

:::warning[Only additive columns migrate in place]

A column riptide added in a later release is appended to an existing `flows` table at startup in manage mode, or by re-running `riptide onboard` in validate mode.
Any other schema change between riptide versions is not applied.
The startup column check fails fast, and the operator must drop the `flows` table (riptide recreates it in manage mode) or re-provision it in validate mode.
Dropping the table discards its rows. Plan retention accordingly.

:::

Enrichment results are denormalized into the flow row at write time: exporter address, resolved interface data (`inputSnmpIfName`, `inputSnmpIfAlias`, `inputSnmpIfSpeed` and the `output…` counterparts), hostnames, classification and locality.
Queries never need join-time lookups.

## Identity columns {/* #identity-columns */}

| Column | Default | Role in the `flows` sort key |
| --- | --- | --- |
| **`tenant`** | `default` | First |
| **`organisation`** | `default` | Second |
| **`zone`** | `default` | Not in the key; a filter dimension |
| **`system`** | host name | Not in the key; a filter dimension |

`flows` sorts by `(tenant, organisation, toStartOfHour(timestamp), srcAs, dstAs, srcAddr, dstAddr, srcPort, dstPort)` and partitions by `toYYYYMMDD(timestamp)`.
How `tenant` and `organisation` anchor hard isolation is on [Multi-tenancy](../architecture/multi-tenancy.md#the-identity-model).

## Server requirement for the write barrier {/* #server-requirement */}

Custom settings with the `SQL_` prefix must be enabled for the multi-tenant `CHECK` barrier.
This is server config, not an environment variable:

```xml
<!-- /etc/clickhouse-server/config.d/custom-settings.xml -->
<clickhouse>
    <custom_settings_prefixes>SQL_</custom_settings_prefixes>
</clickhouse>
```

Riptide never emits a `CHECK` constraint itself; the constraints, users, roles, quota and row policies come from [`riptide onboard`](provisioning-cli.md).
The mechanism and its guarantees are on [Multi-tenancy](../architecture/multi-tenancy.md#write-isolation-multi-tenant).

## Rollup tables {/* #rollups */}

Four 1-minute `SummingMergeTree` rollups sit beside `flows`, each fed by a materialized view named `<table>_mv`.
Query the table, never the `_mv`.

| Table | Dimensions beyond the shared preamble |
| --- | --- |
| **`flows_by_application_1m`** | `application`, `protocol`, `samplingInterval`, `flowProtocol` |
| **`flows_by_conversation_1m`** | `srcAddr`, `dstAddr`, `application`, `samplingInterval`, `flowProtocol` |
| **`flows_by_exporter_iface_1m`** | `exporterAddr`, `exporterName`, `inputSnmp`, `outputSnmp`, `samplingInterval`, `flowProtocol` |
| **`flows_by_geo_asn_1m`** | `srcAs`, `dstAs`, `srcCountry`, `dstCountry`, `samplingInterval`, `flowProtocol` |

Every rollup carries the same preamble and measures:

| Column | Type | Combined by |
| --- | --- | --- |
| **`tenant`**, **`organisation`**, **`timestamp`**, **`zone`** | `String`, `String`, `DateTime('UTC')`, `String` | Preamble. `timestamp` is `toStartOfMinute` of the raw column and keeps its name, so a time filter ports unchanged. |
| **`bytes`**, **`packets`**, **`flowCount`** | `UInt64` | Summed |
| **`bytesIn`**, **`bytesOut`**, **`packetsIn`**, **`packetsOut`** | `UInt64` | Summed. A flow with `direction = UNKNOWN` counts in neither, so use `bytes` unless you want one direction. |
| **`samplingProvenanceMask`** | `SimpleAggregateFunction(groupBitOr, UInt8)` | Merged with `groupBitOr`, not summed. A row aggregated before the column existed reads `0`, "no provenance recorded". |

`samplingInterval` is a `Float64` and `flowProtocol` a `LowCardinality(String)` here, not the `Enum8` the raw table uses.
Rows aggregated before each was appended read `0` and `''`; the two boundaries do not coincide because the columns were appended in different releases.
Neither is offered as something to group by.
Why the rollups carry them, and the boundary predicates a query needs, are on [Sampling rates and provenance](../architecture/sampling.md) and [Query sampling-corrected volume](../guides/sampling-corrected-volume.md).

The physical column order of `flows_by_application_1m`, read from the server:

```sql
SELECT arrayStringConcat(groupArray(name), ', ') AS columns
FROM (SELECT name, position FROM system.columns
      WHERE database = 'riptide' AND table = 'flows_by_application_1m' ORDER BY position);
```

Expected output:

```text
tenant, organisation, timestamp, zone, application, protocol, samplingInterval, flowProtocol, bytes, packets, flowCount, bytesIn, bytesOut, packetsIn, packetsOut, samplingProvenanceMask
```

A rollup gains dimensions in place, so the list is the server's, not this page's.

## Dead-letter table

`flows_dead_letter` holds the rows of a refused batch, see [Inspect and replay dead letters](../operations/dead-letters.md).

| Column | Type |
| --- | --- |
| **`tenant`** | `String` |
| **`failedAt`** | `DateTime64(3, 'UTC')` |
| **`error`** | `String` |
| **`payload`** | `String` (one row as JSON) |

The table carries no `CHECK` constraint and the same tenant row policy as `flows`.

## Retention

| Table | TTL | Set by |
| --- | --- | --- |
| **`flows`** | 30 days | `TTL timestamp + INTERVAL <n> DAY` at creation. In provisioned mode `onboard --ttl-days` (default 30, maximum 10950) applies to a table that run creates. |
| **`flows_dead_letter`** | 30 days | Same as the raw table, and the same `--ttl-days` when a run creates it. |
| **`flows_by_*_1m`** | 365 days | At creation. Change it with `ALTER TABLE <db>.<rollup> MODIFY TTL timestamp + INTERVAL <n> DAY`. |

:::warning[Raw retention above 365 days inverts the invariant]

`--ttl-days` sets only the raw table's TTL; the rollups stay at 365 unless altered.
A raw retention above 365 makes the rollups expire before the raw rows, so long-range queries lose the oldest aggregates while the raw data still exists.
If you set `--ttl-days` above 365, raise every rollup to at least the same value with `ALTER TABLE <db>.<rollup> MODIFY TTL timestamp + INTERVAL <n> DAY`.

:::

## Messages

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `ClickHouse at <endpoint> did not answer within <window> (<n> attempt(s); last cause: …)` | Nothing answered within `riptide.clickhouse.startup-wait` | Start the server, check `riptide.clickhouse.endpoint`, or raise the wait |
| `flows table not found in database '…'` | Validate mode against a database with no `flows` table; or an earlier riptide against ClickHouse 26.8, whose schema endpoint the old client could not parse | Run `riptide onboard --create-schema`; upgrade riptide (it now reads `system.columns`, which both versions answer identically) |
| `… is missing expected column(s) …` | The on-disk `flows` table predates a column riptide inserts | Manage mode adds it on the next start; validate mode: re-run `riptide onboard` |
| `No serializer found for column '…'` | A plain `DEFAULT` column riptide has no value for | Make it `MATERIALIZED` or `ALIAS`, or drop it |
| `riptide.clickhouse.batch.shutdown-grace-period (…) must be at least twice max-latency (…)` | Grace period too short for the flusher to notice the stop signal | Raise the grace period or lower `max-latency` |
| `Failed to persist a batch of N flows, flusher does not retry, some may be committed` | The server refused a batch, see [Insert batching and dead letters](../architecture/persistence.md) | Read `flows_dead_letter`, see [Inspect and replay dead letters](../operations/dead-letters.md) |
| `Code: 469 … (VIOLATED_CONSTRAINT)` | A row's `tenant` or `organisation` does not match the writer credential's `CONST` setting | Fix `riptide.identity.*` on that collector |
| `Code: 452 … (SETTING_CONSTRAINT_VIOLATION)` | A `SET` or query-level `SETTINGS SQL_tenant=…` tried to override the pinned setting | None needed; the pin is working |
| `Rollup <X> does not match this version's schema …`, `could not be verified`, `cannot be reached`, `has no materialized view writing to it`, `left as it is` | Rollup shape drift or a missing grant | [Recover from a rollup shape message](../operations/rollup-drift.md) |
| `coverage_warning: answered from <table>, which holds data from <time>. This answer covers <n> of the <m> minutes you asked for …` | The table that answered a query does not reach the start of the range, or the range exceeded riptide's cap of 43200 minutes on a single query | Informational, see [Rollups](../architecture/rollups.md#the-fallback-is-bounded-by-raw-retention) |
