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
- **A missing, empty or whitespace-only file skips the cycle** — deletion is
  indistinguishable from an atomic replacement in progress, and a shell `>` redirect
  truncates before writing, so the running config is kept and nothing is counted as a
  failure. Both the config file and the inventory file behave this way. The skip warns
  once per episode, not once per poll. Removing the file layer for real requires a
  restart.
- **A skipped cycle leaves the gauges where they were** — a skip decides nothing about
  whether disk and serving agree, so `*.reload.stale` is not recomputed and not latched.
  A file that has been truncated for an hour therefore reads `stale=0`. Alert on the
  once-per-episode warning above, or on the absence of successful reloads; the stale
  gauge answers "did the last file we could read commit", not "is the file on disk
  serving".
- On a successful reload the SNMP interface cache and the SOPS decrypted-file cache
  refresh; exporter-pushed interface names (option records) are kept — they describe
  devices, not configuration. Reloads trigger on **config-file changes only**: after
  rotating a SOPS secrets file, touch or edit `config.yaml` so the decrypted cache
  drops and the next poll picks up the new secret.
- **The gauges exist only while reloading is enabled** — `config.reload.stale` /
  `inventory.reload.stale` and the dead-schedule gauges below are absent when
  reloading is disabled. Absence means "not watching"; a 0 means "the last file we could
  read is what is serving" — not "the file on disk is serving", because a skipped cycle
  reads no file and changes no gauge. Alert on absence separately if hot-reload is
  mandatory in your deployment.
- **A dead schedule is visible** — `config.reload.dead` / `inventory.reload.dead`
  read 1 if the poll schedule stopped and will never run again (the realistic cause:
  an `Error` such as OOM on an oversized file mid-read). Alert on `> 0`; the only
  recovery is a restart.
- **Shutdown counts nothing** — an interrupt landing mid-poll during an orderly stop
  is not a reload failure: no counter moves and no stale latch is set.

Limitations: profile-activated YAML documents and nested `spring.config.import` inside
the reloaded file are boot-only; `env://` secret references cannot rotate in-process
(the environment is immutable per process) — those need a restart.

:::note[Alerting contract change]

A whitespace-only `config.yaml` used to increment `config.reload.failures` and latch `config.reload.stale`.
It is now a skip, matching this page's description and what the inventory file has always done.
If you alert on `config.reload.failures` to catch a `> config.yaml` truncation, that alert stops firing: the truncation now surfaces as the once-per-episode warning above and as reloads that stop happening, not as a failure.

:::

## Classification rule reloads

The classification rules are a separate family with a separate posture, and their own opt-in schedule.

The resource named by `riptide.classification.rules` is parsed once, eagerly, while the context starts: an unreadable or unparseable resource fails the boot there, naming the parse error.
For an `http(s)://` resource that eager parse is a network fetch, so **a rules server that is down is a startup outage** — the collector will not come up until it answers. Weigh that against the convenience of serving one ruleset to a fleet; a local file with a configuration-management tool writing it has no such coupling.
The engine then loads those rules into its decision tree on a background thread.
Afterwards, nothing re-reads the resource unless you configure an interval:

```properties
riptide.classification.reload-interval=5m   # absent or 0 = disabled (the default)
```

With an interval set, the rules resource is polled on that schedule and a change applies without a restart.
The resource is any Spring resource location, so this covers `file:/etc/riptide/classification-rules.csv` as readily as an `http://rules.internal/riptide.csv` endpoint serving one ruleset to a fleet.
Point it at a file or a URL, not at the bundled `classpath:` default: a classpath resource inside the packaged jar cannot change, so the schedule polls it forever and never has anything to apply.

Semantics, which are the config reloader's (same poll loop) with a source that can be a URL:

- **Content-hash polling** — the resource is re-resolved and its bytes hashed every cycle. Unchanged bytes rebuild nothing: the hash decides, not the clock. A cycle that finds no change costs one fetch and no work; a cycle that finds one costs two, because the engine re-reads the resource itself when it rebuilds. Startup costs three (the eager parse, the engine's first load, and the schedule's own baseline).
- **A remote fetch is bounded end to end** — 10 seconds to connect, 10 seconds for each read, **and** 10 seconds for the whole response. The last of those is the one that matters: a server sending one byte at a time resets a per-read timer forever, so only a deadline across the response ends the cycle. Worst case is roughly twice the bound, because a read already blocked when the deadline passes still has to time out on its own. A response larger than 8 MiB is refused unread rather than buffered.
- **Only a 200 is a ruleset** — any other status is a failure naming the code, so a redirect this fetch does not follow, a 5xx, or a proxy's error page served as HTML never reaches the CSV parser. The one exception is 404, which is absence and skips.
- **A source that is not there skips** — a 404, or a deleted file. The last good rules keep classifying, nothing is counted as a failure, and the skip warns once per episode rather than once per poll. So does a response with an empty or whitespace-only body: an empty ruleset is never committed. A *local* file that is present but unreadable (a permission denial) is a failure, not a skip — telling an operator to make a file reappear when it is already there would send them the wrong way.
- **A failed fetch or a failed load keeps the last good rules serving** — flows keep being classified by whatever loaded last, and nothing is thrown at a flow. See the two cases below.
- **What was published is logged, and rejected rules are named** — every load that publishes logs how many rules it published, and a WARN naming any rule the engine could not use. A rejected rule is not a failed reload: the rest of the ruleset serves, `classification.reload.successes` moves and `classification.reload.stale` stays 0. So this WARN is the only signal that part of an edit is classifying nothing — and it is a **log** signal with no metric behind it, so nothing at `/metrics` will tell you. Watch the log after a ruleset change, or ship it somewhere you can alert on. This one line does not depend on the interval: the ruleset loaded at startup is reported the same way whether or not a schedule is configured. The rejected rules are named up to the first 20, then summarised as a count.
- **A ruleset that failed to load is attempted once** — not once per interval. Bytes that would not parse this cycle will not parse next cycle, so a retry loop would rebuild nothing and bury the first, real failure under one per interval. The failure stays counted and holds the stale gauge at 1; fix the ruleset and the next poll picks the fix up as an ordinary change.
- **No authentication** — no credentials are sent, no conditional `GET`, no ETag or `Last-Modified` handling; the endpoint must answer an unconditional `GET`. Protect it at the network layer. Credentials embedded in the location (`http://user:token@…`) are **not** a supported way to authenticate; they are redacted wherever Riptide logs the location, but they still travel in the clear.

| Metric | Meaning |
|---|---|
| `classification.reload.successes` | Loads that published a ruleset. A healthy start leaves this at 1. |
| `classification.reload.failures` | Reloads that did not happen: a fetch that failed, or a load that threw. Not latched: every attempt that fails counts again. |
| `classification.reload.stale` | 1 when the last fetch or load attempt failed and no later one has succeeded; 0 otherwise. |
| `classification.reload.dead` | 1 if the poll schedule stopped and will never run again. Present only while an interval is configured. |

**One family, two layers.** Fetching the rules and loading them are done by different parts — the reload schedule fetches, the engine loads — and they report on the same three series rather than on two families that could disagree.
They cannot double-count: a fetch that fails never reaches the engine, and a ruleset that fails to parse was fetched successfully.
`classification.reload.stale` covers both halves, so 1 means "the rules that are serving are not the rules the source has", whichever half is at fault; the log line names which.

**A skipped cycle leaves the gauges where they were.** A skip decides nothing about whether the source and what is serving agree, so `classification.reload.stale` is not recomputed and not latched: an endpoint that has answered 404 for an hour reads `stale=0`, and so does a ruleset that has been empty all day. This is the same trap the config reloader carries, and it matters more here because the whole page tells you to alert on `stale`. Alert on the once-per-episode warning as well, or on the absence of successful reloads.

**A dead schedule is visible.** `classification.reload.dead` reads 1 if the poll schedule stopped and will never run again (the realistic cause: an `Error` such as OOM mid-cycle). Alert on `> 0`; the only recovery is a restart.

Unlike `config.reload.stale` and `inventory.reload.stale`, **`classification.reload.stale`** is registered unconditionally, including when no interval is configured.
It claims less than they do: they assert a relationship between a file on disk and what is serving, so a permanent 0 would falsely read "in sync", while this one asserts only "the last attempt failed and has not recovered".
With no interval, 0 is simply true.
**`classification.reload.dead`** follows the other reloaders instead and is **absent** with no interval configured — a dead-schedule gauge reading 0 would claim there is a schedule.

What a failure does depends on whether any rules ever loaded:

- **Rules already serving** — nothing an operator or a flow can see changes. A rebuild publishes atomically, so a failed one leaves the previous rules classifying, complete. The failure is reported by a WARN naming the cause, plus the counter and the gauge. This is the case where the gauge is the only durable signal: no flow fails and no error is logged.
- **No rules ever loaded** — classification is unavailable. Every flow's classification throws and an ERROR is logged. Reaching this needs the resource to become unreadable between the eager startup parse and the background load a moment later; the window is narrow, but the context starts normally and stays up, so the ERROR and `classification.reload.stale` at 1 are the only signals. With a reload interval configured this recovers on its own in the ordinary case: the schedule could not read a baseline either, so the first poll that reads anything hands it to the engine. Only if the resource became readable in the window *between* the schedule taking its baseline and the next poll does recovery wait for the rules to actually change, because from then on the hash decides. Restarting once the resource is readable resolves both.

Shutdown counts nothing here either: a reload interrupted or refused during an orderly stop moves no counter and latches no gauge.

Dots become underscores at `/metrics` (see [Metrics endpoint](#metrics-endpoint)), so the series to alert on is `classification_reload_stale`.

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
| `persister.batch.failedRows` | rows in batches whose insert failed |

`failedRows` covers four cases, and is an **upper bound** on the loss rather than an exact count of it in two of them.
A *refused* insert may still have committed a prefix of the batch, yet the whole batch is charged here (see [insert batching](../configuration/clickhouse.md#insert-batching-batch)); the same is true when an unexpected `Error` escapes the flusher, since it may escape with an insert already in flight.
The other two are certain loss: rows the flusher still held when it was interrupted, and rows left over once the shutdown grace period expires. Neither ever reached the server.

Delivery accounting: `recordsScheduled − dispatchDrops − dispatchErrors` is what reached the
persister. Note `recordsDispatched` counts only records the pipeline accepted without throwing, so
it excludes `dispatchErrors`.
That arithmetic stops at the persister: do **not** extend it to persisted rows by subtracting `failedRows`, because a refused insert counted in full there may have committed part of its batch. Query the table for what landed.

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
