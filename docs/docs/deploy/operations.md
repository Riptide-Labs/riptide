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

Node and routing configuration can reload from `/etc/riptide/config.yaml` without a
restart — adding a device or changing a subnet applies within one poll. Opt in with:

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

Limitations: profile-activated YAML documents and nested `spring.config.import` inside
the reloaded file are boot-only; `env://` secret references cannot rotate in-process
(the environment is immutable per process) — those need a restart.

## Upgrading

Compose: `docker compose pull && docker compose up -d`. Plain JAR: replace the jar,
restart. Configuration is backward-compatible within a minor line; breaking configuration
moves are logged loudly at startup (e.g. the pre-0.1.0 `riptide.snmp.config.definitions`
tree logs an explicit error pointing at `riptide.nodes`).

Upgrading to 0.6.7 also changes what one **metric** means rather than any configuration key — see
[Parser gauges: exporters and templates](#parser-gauges-exporters-and-templates) before relying on
`parsers.<name>.sessionCount`.

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

**Two of these count loss that happens before any of the queues.** They were added because a lab
measurement found the application accounting for only ~4% of a ~25% shortfall under sustained
overload, with nothing accounting for the rest:

- `socketDrops` is **upstream of every application counter**. Once the receive buffer overflows, the
  datagram is gone before riptide runs, so this is the only place that loss is visible at all. It is
  read per socket from `/proc/net/udp`, so it attributes to this receiver rather than the whole host,
  and it publishes no value on non-Linux (absent is not the same as zero). A rising value means the
  collector cannot drain the socket fast enough — raise `net.core.rmem_max`, or reduce offered load.
- `undecodableSets` counts Data Sets thrown away because their Template had not arrived. RFC 7011 §8
  permits discarding these, so it is not a protocol error, but it is still lost data. It counts
  **Sets, not records**: without the Template the record size is unknown, so treat it as a lower
  bound. Expect a burst at startup — a UDP exporter re-announces Templates only periodically, so a
  freshly started collector discards data until the first Template of each exporter arrives.
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

Two gauges describe what a UDP parser is holding. They are easy to confuse, and until 0.6.7
`sessionCount` reported the wrong one of the two.

| Metric | Meaning |
|---|---|
| `parsers.<name>.sessionCount` | exporters — one per `(session, observation domain)` pair |
| `parsers.<name>.templateCount` | templates held across all exporters |

**What changed in 0.6.7.** `sessionCount` used to report the **template** total, so it overstated by
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

:::warning

Riptide does not export metrics yet: there is no reporter and no scrape endpoint, and the management
port serves only `/livez` and `/readyz`. These gauges are registered in the metrics registry, but
nothing publishes them — so rebaselining a dashboard is something to do when metric export lands,
not today.

:::

## Health endpoints & probes

Riptide serves two plain-HTTP health endpoints on a management port (default `8080`) — no auth, no
TLS, cluster-internal. They're built on the JDK HTTP server, so the collector stays headless (no
application web server).

| Endpoint | Meaning |
|---|---|
| `GET /livez` | **Liveness** — the receiver event loops are alive. Returns `200` while booting and once running; `503` only if a started receiver's socket has died. **Never** checks ClickHouse. |
| `GET /readyz` | **Readiness** — all configured receivers are bound and listening (`200`), else `503`. |

Configure via `riptide.management.*`:

```properties
riptide.management.enabled=true       # set false to disable the endpoints entirely
riptide.management.port=8080
riptide.management.bind-address=0.0.0.0
```

**Readiness deliberately excludes ClickHouse.** There is no write buffer and a single collector has
no failover, so making the collector "not ready" during a ClickHouse blip would only drain the load
balancer (with `externalTrafficPolicy: Local`) and lose *more* flows at the edges — for no benefit.
Readiness reflects receiver health only; a ClickHouse outage keeps the collector receiving.

Kubernetes probe mapping:

```yaml
startupProbe:   { httpGet: { path: /readyz, port: 8080 }, failureThreshold: 30, periodSeconds: 2 }
livenessProbe:  { httpGet: { path: /livez,  port: 8080 } }
readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
```

The Compose stack uses `/readyz` as the service `healthcheck` (via the image's BusyBox `wget`).

## Ports

| Port | Protocol | What |
|---|---|---|
| `9999/udp` | NetFlow/IPFIX | default flow ingest (container `EXPOSE`; receivers are configurable) |
| `8080` | HTTP | management / health endpoints (`/livez`, `/readyz`) |
| `8123` | HTTP | ClickHouse (stack-internal unless you expose it) |
