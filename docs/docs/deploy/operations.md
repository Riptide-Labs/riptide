---
sidebar_position: 5
title: Operations
---

# Operations notes

## Image tags

| Tag | Meaning | Use for |
|---|---|---|
| `:<version>` | immutable release | production (pin this) |
| `:latest` | newest release | quickstarts |
| `:rc` | floating, rebuilt on **every merge to main** | tracking development — at your own risk |

## Restarts and data

In manage mode (`riptide.clickhouse.manage-schema=true`, the default), Riptide ensures its
`flows` table with `CREATE TABLE IF NOT EXISTS` at startup — an existing table is not
replaced, so **flow data now survives a Riptide restart**. (This fixes the earlier
`CREATE OR REPLACE` behavior, which recreated the table on every boot and lost all data.)

:::warning

Schema evolution is still not migrated automatically. `CREATE TABLE IF NOT EXISTS` no-ops on
an existing table, so a schema change between Riptide versions is **not** applied — the
startup column check fails fast if the on-disk schema is stale, and the operator must **drop
the `flows` table** (Riptide recreates it in manage mode) or re-provision it in
`manage-schema=false` mode. This is a deliberate fail-fast until schema migrations land; plan
retention accordingly if a version upgrade requires dropping the table.

:::

## Config hot-reload

Credential sets, polling profiles and routing reload from `/etc/riptide/config.yaml`
without a restart, and the [inventory file](../configuration/agent-configuration.md)
reloads on its own content changes — adding a device or carving a range out applies
within one poll. Opt in with:

```properties
riptide.config.reload-interval=30s   # absent or 0 = disabled (the default)
```

Semantics:

- **Content-hash polling, not inotify** — the path is re-resolved and the content
  hashed every cycle, so bind mounts, Kubernetes ConfigMap symlink swaps, and
  mtime-insensitive writers are all picked up reliably.
- **Layering is preserved** — environment-variable overrides keep their precedence
  over the file, exactly as at boot. A file created after startup slots in beneath
  the environment as well.
- **Bad config never wins** — candidates run the same validation as startup; a failing
  reload keeps the running configuration, logs a warning naming the problem, and
  raises `config.reload.failures` plus a `config.reload.stale` gauge (alert on it).
- **A missing or empty file skips the cycle** — deletion is indistinguishable from an
  atomic replacement in progress, so the running config is kept. Removing the file
  layer for real requires a restart.
- On a successful reload the SNMP interface cache and the SOPS decrypted-file cache
  refresh; exporter-pushed interface names (option records) are kept — they describe
  devices, not configuration. Reloads trigger on **config-file changes only**: after
  rotating a SOPS secrets file, touch or edit `config.yaml` so the decrypted cache
  drops and the next poll picks up the new secret.
- **The gauges exist only while reloading is enabled** — `config.reload.stale` /
  `inventory.reload.stale` and the dead-schedule gauges below are absent when
  reloading is disabled. Absence means "not watching"; a 0 always means "watching and
  in sync". Alert on absence separately if hot-reload is mandatory in your deployment.
- **A dead schedule is visible** — `config.reload.dead` / `inventory.reload.dead`
  read 1 if the poll schedule stopped and will never run again (the realistic cause:
  an `Error` such as OOM on an oversized file mid-read). Alert on `> 0`; the only
  recovery is a restart.
- **Shutdown counts nothing** — an interrupt landing mid-poll during an orderly stop
  is not a reload failure: no counter moves and no stale latch is set.

Limitations: profile-activated YAML documents and nested `spring.config.import` inside
the reloaded file are boot-only; `env://` secret references cannot rotate in-process
(the environment is immutable per process) — those need a restart.

## Upgrading

Compose: `docker compose pull && docker compose up -d`. Plain JAR: replace the jar,
restart. Configuration is backward-compatible within a minor line; breaking configuration
moves are impossible to miss: the removed trees **fail startup** (`riptide.nodes` and the
retired fleet poll keys), while superseded-but-harmless ones log an explicit error and are
ignored (`riptide.snmp.config.definitions`). The 0.9 flag day is the big one:
any surviving `riptide.nodes` key, in any spelling including the `RIPTIDE_NODES_*`
environment form, stops the collector with an error naming the key and the converter —
see [Upgrading from 0.8](../upgrading-from-0.8.md). Plan it as a migration step, not as a
log-review item: under systemd or Kubernetes a missed key means a restart loop until the
configuration is converted.

Upgrading to 0.7.0 also changes what one **metric** means rather than any configuration key — see
[Parser gauges: exporters and templates](#parser-gauges-exporters-and-templates) before relying on
`parsers.<name>.sessionCount`.

**NetFlow v5 sampling rates change on upgrade.** riptide now reads the sampling rate a v5 exporter
states in its packet header, where it previously ignored it. Stored `bytes` and `packets` are
untouched and nothing fails, but any query multiplying by `samplingInterval` returns a different
answer for v5 rows written from here on, and older rows are not rewritten. Watch
`parsers.<name>.samplingRate.header` to see which receivers are now resolving from the header. Full
detail, including how to identify affected exporters and how to pin the old behaviour, is in
[Sampling rate](../configuration/receivers.md#sampling-rate).

**A `samplingProvenance` column is added to `flows` on upgrade.** It records which rung of the
resolution ladder supplied each row's `samplingInterval`, so a stored `1` stops being ambiguous
between an exporter that said it does not sample and one that said nothing at all. In manage mode
the column is added in place with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`: no operator action, no
data loss, no rewrite. A provisioned deployment (`manage-schema: false`) fails fast naming the
column — re-run `riptide onboard` to add it. Existing rows read `''`, which means "written before
this column existed" and is deliberately distinct from `assumed`; they are not backfilled, because
the information needed to reconstruct them was never recorded. No existing column, value or query
result changes. See [Where a rate came
from](../configuration/receivers.md#where-a-rate-came-from).

## Ingest loss counters

Flows can be dropped at two bounded queues, and each one counts what it discards — nothing is
lost silently. Alert on the drop counters; watch the depth gauges for early warning.

| Metric | Meaning |
|---|---|
| `listeners.<name>.socketDrops` | **datagrams the kernel discarded** because the socket receive buffer was full (gauge, Linux only) |
| `parsers.<name>.undecodableSets` | Data Sets discarded because their IPFIX/NetFlow v9 Template was not known |
| `parsers.<name>.dispatchQueueDepth` | packets waiting to be enriched (gauge) |
| `parsers.<name>.dispatchDrops` | **records** discarded because enrichment/persistence fell behind, or discarded at shutdown |
| `pipeline.dispatchErrors` | records lost because enrichment or persistence threw |
| `persister.batch.queueDepth` | rows waiting to be inserted (gauge) |
| `persister.batch.droppedRows` | rows discarded because ClickHouse could not keep up |
| `persister.batch.failedRows` | rows in batches that failed to insert |

Delivery accounting: `recordsScheduled − dispatchDrops − dispatchErrors` is what reached the
persister. Note `recordsDispatched` counts only records the pipeline accepted without throwing, so
it excludes `dispatchErrors`.

**Two of these count loss that happens before any of the queues.**
They were added because a lab measurement found the application accounting for only ~4% of a ~25% shortfall under sustained overload, with nothing accounting for the rest:

- `socketDrops` is **upstream of every application counter**.
  Once the receive buffer overflows, the datagram is gone before riptide runs, so this is the only place that loss is visible at all.
  It is read per socket from `/proc/net/udp`, matched on the bound address and port, so it attributes to this receiver rather than to the whole host or to another socket sharing the port number.
  It publishes no value on non-Linux platforms (absent is not the same as zero).
  A rising value means the collector cannot drain the socket fast enough: raise `net.core.rmem_max`, or reduce offered load.
- `undecodableSets` counts Data Sets thrown away because their Template had not arrived.
  RFC 7011 §8 permits discarding these, so it is not a protocol error, but it is still lost data.
  It counts **Sets, not records**: without the Template the record size is unknown, so treat it as a lower bound.
  Note that it also counts Options Data Sets, whose loss costs enrichment metadata (exporter-pushed interface names) rather than flow records, so a non-zero value is not proof that flow data was lost.
  Expect a burst at startup: a UDP exporter re-announces Templates only periodically, so a freshly started collector discards data until the first Template of each exporter arrives.
  Sustained non-zero values are the ones to alert on.

**Datagram vs. reliable transports differ deliberately.** A UDP receiver drops when its dispatch
queue stays full, because the medium is already lossy and a counted userspace drop beats pushing
back into the kernel receive buffer where the loss is invisible. An IPFIX/**TCP** receiver never
drops here — the exporter's bytes are already acknowledged and there is no retransmission, so the
listener blocks instead, which closes the TCP receive window and makes the exporter slow down.

### Memory budget for the queues

Both queues are bounded, so the worst case is the **sum**, and the dispatch queue costs more than
its flow objects:

- `parsers.<name>` dispatch queue: 4096 packets by default. Each queued packet also pins its
  received datagram buffer until the packet is enriched — about **33 MB of direct memory per
  receiver** at the default 8096-byte buffer size, on top of the heap cost of the flow objects.
- `persister.batch` queue: 40,000 rows by default (`riptide.clickhouse.batch.queue-capacity`).

A `multi` receiver runs one parser per sub-protocol, each with its own queue and threads, so budget
per sub-protocol and size down accordingly if you configure several.

## Parser gauges: exporters and templates

Two gauges describe what a UDP parser is holding. They are easy to confuse, and until 0.7.0
`sessionCount` reported the wrong one of the two.

| Metric | Meaning |
|---|---|
| `parsers.<name>.sessionCount` | exporters — one per `(session, observation domain)` pair |
| `parsers.<name>.templateCount` | templates held across all exporters |

These three (with `dispatchQueueDepth` above) are **registered while the parser runs and deregistered when it stops**, so a stopped receiver publishes no series at all rather than a final or zero reading.
Alert on absence, not on a value: a rule like `parsers_<name>_sessionCount == 0` goes stale instead of firing, because a stopped parser previously reported its last counts forever while a stopped dispatch queue read `0`, which is indistinguishable from healthy.

## NetFlow v5 sampling rate resolution

NetFlow v5 has no options table, so a v5 flow's sampling rate resolves from the packet header, then
the receiver's `flow-sampling-interval-fallback`, then an assumed `1`. Which rung answered is metered
per **packet** (the rate lives in the header, so every record in a packet resolves identically) and
per receiver.

| Metric | Meaning |
|---|---|
| `parsers.<name>.samplingRate.header` | packets whose rate came from the exporter's header |
| `parsers.<name>.samplingRate.fallback` | packets that fell through to the configured rate |
| `parsers.<name>.samplingRate.assumed` | packets with no rate anywhere, recorded as `1` |

`assumed` is not the same statement as an exporter reporting a rate of `1`.
An exporter that states `1` has said it does not sample, and that lands under `header`.
`assumed` means nothing stated a rate at all, and `1` is what riptide wrote in the absence of one.

Each meter's leaf name is the value written to that flow's `samplingProvenance` column, so a meter and the rows it counted always agree.
The meters answer "is this happening now" without a query; the column answers it for any period, for every protocol, and per exporter:

```sql
SELECT exporterAddr, samplingProvenance, samplingInterval, count() AS flows
FROM flows
WHERE timestamp > now() - INTERVAL 1 HOUR
GROUP BY exporterAddr, samplingProvenance, samplingInterval
ORDER BY exporterAddr, flows DESC
```

One exporter appearing under two provenances is a rate that is not resolving consistently — firmware populating the sampling field on some export paths and not others, or a v9 sampler options table expiring between refreshes.
See [Where a rate came from](../configuration/receivers.md#where-a-rate-came-from) for the full vocabulary.

These exist because the resolution is invisible in the data path: riptide records the rate without
applying it, so an exporter that starts or stops advertising changes no counter and raises no error.
A `header` rate that falls to zero means a fleet stopped advertising and is now being recorded at an
assumed `1` — or on the configured rate, which may not match. See
[Sampling rate](../configuration/receivers.md#sampling-rate) for the resolution order and settings.

**What changed in 0.7.0.** `sessionCount` used to report the **template** total, so it overstated by
however many templates each exporter announces. It now reports `(session, observation domain)` pairs.
Expect the value to **drop** on upgrade, by roughly the templates-per-exporter factor; the previous
quantity is still available, under the name that describes it — `templateCount`. This changes what a
metric *means*, not any configuration key.

What moves `sessionCount` is a new exporter appearing, or an exporter's last template expiring and
housekeeping reaping it. A steady-state re-announcement of a template the exporter has already sent
moves **neither** gauge: `addTemplate` replaces the entry under the same template id.

**It is not a count of exporting processes.** A session is keyed by remote address plus the local
socket, and each observation domain within a session counts separately, so:

- one process announcing two observation domains counts **2**
- one process sending to two receiver ports counts **2**
- two processes behind one NAT address, sharing an observation domain, count **1**

**Only IPFIX and NetFlow v9 populate these gauges.** NetFlow v5 and sFlow carry no templates, so both
gauges stay **0** for those receivers no matter how many exporters are sending — a 0 here is not an
ingest fault, and the drop-on-upgrade note above does not apply to them. On a `multi` receiver each
sub-protocol registers its own pair under its own name (`<name>:netflow5`, `<name>:sflow`, …), so
those pairs read 0 while the IPFIX and NetFlow v9 pairs report real values.

`sessionCount` is only eventually consistent with "holds at least one template": housekeeping expires
templates in one pass and reaps the emptied exporters in a second, so the gauge can transiently
include an exporter holding none. An alert on it has to tolerate that flap.

Template cardinality is the more useful of the two for capacity work — it is what drives the
per-record cost of the parse path. For the drop and depth metrics, see
[Ingest loss counters](#ingest-loss-counters) above.

:::tip

These gauges, and every other metric on this page, are published on the management port at
`GET /metrics` in Prometheus text format. See [Metrics endpoint](#metrics-endpoint) below.

:::

## Health endpoints & probes

Riptide serves two plain-HTTP health endpoints on a management port (default `8080`) — no auth, no
TLS, cluster-internal. They're built on the JDK HTTP server, so the collector stays headless (no
application web server).

| Endpoint | Meaning |
|---|---|
| `GET /livez` | **Liveness** — the receiver event loops are alive. Returns `200` while booting and once running; `503` only if a started receiver's socket has died. **Never** checks ClickHouse. |
| `GET /readyz` | **Readiness** — all configured receivers are bound and listening (`200`), else `503`. Zero configured receivers reports ready (see the contract notes below). |
| `GET /metrics` | **Metrics** — the full metric registry in Prometheus text format. See below. |

Configure via `riptide.management.*`:

```properties
riptide.management.enabled=true         # set false to disable the endpoints entirely
riptide.management.port=8080
riptide.management.bind-address=0.0.0.0
riptide.management.metrics-enabled=true # set false to serve probes but not /metrics
```

**Readiness deliberately excludes ClickHouse.**
Flows arrive as UDP push: a "not ready" collector does not stop the packets, it only moves the loss to another layer (a drained load balancer under `externalTrafficPolicy: Local`, or the wire).
When ClickHouse recovers, readiness convergence typically loses more flows than the bounded batching queue (`riptide.clickhouse.batch.queue-capacity`, 40,000 rows by default) absorbs.
At the measured ~11.8k rows/s the queue covers ~3.4 s, well under a probe period plus endpoint propagation.
And where Prometheus scrapes through the Service, "not ready" can remove the pod from the endpoints and take `/metrics` down with it.
That blinds the one signal that explains the outage, exactly when it fires.
A ClickHouse outage keeps the collector receiving.
Probes are for scheduling; saturation is for alerting: watch a sustained `persister.batch.droppedRows` rate and `persister.batch.queueDepth` approaching the queue capacity.

**Readiness also deliberately tolerates zero configured receivers.**
The shipped configuration declares none, so failing readiness there would turn a fresh install into a pod that never becomes ready.
A collector without receivers logs a startup WARN ("No receivers configured") and reports ready: misconfigured, not unhealthy.

Kubernetes probe mapping:

```yaml
startupProbe:   { httpGet: { path: /readyz, port: 8080 }, failureThreshold: 30, periodSeconds: 2 }
livenessProbe:  { httpGet: { path: /livez,  port: 8080 } }
readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
```

The Compose stack uses `/readyz` as the service `healthcheck` (via the image's BusyBox `wget`).

The endpoints are served on virtual threads, capped by `riptide.management.max-concurrent-requests` (default 32).
Requests beyond the cap are answered `503` rather than queued, so a probe gets a fast answer instead of waiting behind a burst.

## Metrics endpoint

`GET /metrics` renders the whole metric registry in [Prometheus text exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/) 0.0.4.

```bash
curl -s http://localhost:8080/metrics
```

Registry names contain dots; Prometheus metric names may not.
Characters outside `[a-zA-Z0-9_:]` are replaced with `_`, so `enrichment.optionInterfaces.consumed` is scraped as `enrichment_optionInterfaces_consumed`.
Counters are **not** given the conventional `_total` suffix, so a name you find in the source is the name you search for in Grafana.

Type mapping:

| Registry type | Exposed as |
|---|---|
| Gauge (numeric) | `gauge`. Non-numeric gauges are skipped — they have no valid representation, and emitting one would break the entire scrape rather than one series. |
| Counter | `counter` |
| Meter | `counter`, plus `_rate_1m` and `_rate_5m` gauges carrying Dropwizard's own moving averages |
| Histogram | `summary` with p50/p95/p99 and `_count` |
| Timer | `summary` named `<name>_seconds` with p50/p95/p99 and `_count`. Durations are converted from nanoseconds to seconds, the unit Prometheus tooling assumes. |

The endpoint shares the probes' concurrency cap rather than having its own.
Rendering walks the whole registry, so it is the more expensive handler and has more reason to be bounded, not less.
A scrape that loses the race is shed with `503`, which Prometheus records as a failed scrape.

Set `riptide.management.metrics-enabled=false` to serve probes without exposing metric names and values.
The two are separate settings because they have different exposure profiles: probes answer up/down, while metrics describe your exporters and throughput.
With it disabled the path is not registered at all, so a scrape gets `404`.

:::note
`jstack` does not show virtual threads, so the `management-http-*` handlers are invisible to it and to `top -H`.
Their absence from a thread dump means the server is idle, not dead.
To see them, take a dump that includes virtual threads:

```bash
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
```
:::

## Ports

| Port | Protocol | What |
|---|---|---|
| `9999/udp` | NetFlow/IPFIX | default flow ingest (container `EXPOSE`; receivers are configurable) |
| `8080` | HTTP | management endpoints (`/livez`, `/readyz`, `/metrics`) |
| `8123` | HTTP | ClickHouse (the compose stack publishes it on loopback only; password from `CLICKHOUSE_PASSWORD`) |
