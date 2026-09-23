---
title: Management endpoints and ports
sidebar_position: 13
description: The health and metrics endpoints on the management port, their settings, the Prometheus type mapping, the Kubernetes probe mapping, every port riptide and the compose stack use, and what each image tag means.
---

# Management endpoints and ports reference

## Endpoints {/* #health-endpoints--probes */}

Riptide serves plain-HTTP endpoints on a management port, with no auth and no TLS, for cluster-internal use.
They are built on the JDK HTTP server, so the collector stays headless.

| Endpoint | Meaning | Answers |
| --- | --- | --- |
| **`GET /livez`** | Liveness: the receiver event loops are alive. Never checks ClickHouse. | `200` while booting and once running; `503` only if a started receiver's socket has died |
| **`GET /readyz`** | Readiness: all configured receivers are bound and listening. Zero configured receivers reports ready. | `200 ok: receivers listening`, else `503 receivers not listening: <names>` |
| **`GET /metrics`** | The full metric registry in Prometheus text format. | `200`, or `404` with `riptide.management.metrics-enabled=false` |

```bash
curl -s -i http://localhost:8080/readyz
```

Expected output:

```text
HTTP/1.1 200 OK
Date: Wed, 23 Sep 2026 12:37:07 GMT
Content-type: text/plain; charset=utf-8
Content-length: 24

ok: receivers listening
```

Readiness excludes ClickHouse.
Flows arrive as UDP push, so a "not ready" collector does not stop the packets; it only moves the loss to another layer, a drained load balancer under `externalTrafficPolicy: Local` or the wire.
When ClickHouse recovers, readiness convergence typically loses more flows than the bounded batching queue (`riptide.clickhouse.batch.queue-capacity`, 40,000 rows by default) absorbs: at the measured ~11.8k rows/s the queue covers ~3.4 s, well under a probe period plus endpoint propagation.
Where Prometheus scrapes through the Service, "not ready" can also remove the pod from the endpoints and take `/metrics` down with it, blinding the one signal that explains the outage.
A ClickHouse outage therefore keeps the collector receiving.
Probes are for scheduling; saturation is for alerting: watch a sustained `persister.batch.droppedRows` or `persister.batch.failedRows` rate and `persister.batch.queueDepth` approaching the queue capacity.
A non-zero `persister.batch.deadLetterFailedRows` rate is a separate signal: the refused rows are not being kept anywhere.

Readiness tolerates zero configured receivers because the shipped configuration declares none, so failing readiness there would turn a fresh install into a pod that never becomes ready.
A collector without receivers logs a startup WARN (`No receivers configured`) and reports ready: misconfigured, not unhealthy.

Receivers start after the [ClickHouse startup wait](clickhouse.md#startup-wait), so `/readyz` answers `503` for as long as a backend keeps it waiting, up to `riptide.clickhouse.startup-wait` (30 s by default).
That default sits under both probe budgets below: the `startupProbe` allows 30 × 2 s = 60 s, and the compose healthcheck allows `start_period: 20s` plus 3 × 10 s.
Raise the wait and raise those with it, or the orchestrator restarts a collector that was about to come up.

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.management.enabled`** | boolean | `true` | `false` disables the endpoints entirely. |
| **`riptide.management.port`** | int | `8080` | Listening port. |
| **`riptide.management.bind-address`** | string | `0.0.0.0` | Bind address. |
| **`riptide.management.metrics-enabled`** | boolean | `true` | `false` serves the probes but does not register `/metrics`, so a scrape gets `404`. Probes answer up/down; metrics describe your exporters and throughput, which is why the two are separate. |
| **`riptide.management.max-concurrent-requests`** | int | `32` | Requests in flight across all three endpoints. Requests beyond the cap are answered `503` rather than queued, so a probe gets a fast answer instead of waiting behind a burst. |

The endpoints are served on virtual threads.
`jstack` does not show virtual threads, so the `management-http-*` handlers are invisible to it and to `top -H`; their absence from a thread dump means the server is idle, not dead.
To see them, take a dump that includes virtual threads:

```bash
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
```

## Probe mapping

Kubernetes:

```yaml
startupProbe:   { httpGet: { path: /readyz, port: 8080 }, failureThreshold: 30, periodSeconds: 2 }
livenessProbe:  { httpGet: { path: /livez,  port: 8080 } }
readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
```

The compose stack uses `/readyz` as the service `healthcheck`, through the image's BusyBox `wget`:

```yaml
healthcheck:
  test: ["CMD", "wget", "-qO-", "http://localhost:8080/readyz"]
  interval: 10s
  timeout: 3s
  retries: 3
  start_period: 20s
```

## Metrics endpoint {/* #metrics-endpoint */}

`GET /metrics` renders the whole metric registry in [Prometheus text exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/) 0.0.4.

```bash
curl -s http://localhost:8080/metrics | grep -E '^(# TYPE )?persister_batch'
```

Expected output:

```text
# TYPE persister_batch_queueDepth gauge
persister_batch_queueDepth 0.0
# TYPE persister_batch_deadLetterFailedRows counter
persister_batch_deadLetterFailedRows 0.0
# TYPE persister_batch_deadLetteredRows counter
persister_batch_deadLetteredRows 0.0
# TYPE persister_batch_droppedRows counter
persister_batch_droppedRows 0.0
# TYPE persister_batch_failedRows counter
persister_batch_failedRows 0.0
# TYPE persister_batch_batchSize summary
persister_batch_batchSize{quantile="0.5"} 0.0
```

Registry names contain dots; Prometheus metric names may not.
Characters outside `[a-zA-Z0-9_:]` are replaced with `_`, so `enrichment.optionInterfaces.consumed` is scraped as `enrichment_optionInterfaces_consumed`.
Counters are not given the conventional `_total` suffix, so a name you find in the source is the name you search for in Grafana.
The [metrics reference](metrics.md) lists every series by its registry name.

| Registry type | Exposed as |
| --- | --- |
| Gauge (numeric) | `gauge`. Non-numeric gauges are skipped: they have no valid representation, and emitting one would break the entire scrape rather than one series. |
| Counter | `counter` |
| Meter | `counter`, plus `_rate_1m` and `_rate_5m` gauges carrying Dropwizard's own moving averages |
| Histogram | `summary` with p50/p95/p99 and `_count` |
| Timer | `summary` named `<name>_seconds` with p50/p95/p99 and `_count`. Durations are converted from nanoseconds to seconds. |

The endpoint shares the probes' concurrency cap rather than having its own.
Rendering walks the whole registry, so it is the more expensive handler and has more reason to be bounded.
A scrape that loses the race is shed with `503`, which Prometheus records as a failed scrape.

## Ports

| Port | Protocol | What |
| --- | --- | --- |
| **`9999/udp`** | NetFlow/IPFIX | default flow ingest (container `EXPOSE`; receivers are configurable) |
| **`8080`** | HTTP | management endpoints (`/livez`, `/readyz`, `/metrics`); `riptide.management.port` |
| **`8123`** | HTTP | ClickHouse (the compose stack publishes it on loopback only; password from `CLICKHOUSE_PASSWORD`) |
| **`9000`** | TCP | ClickHouse native protocol (the compose stack publishes it on loopback only) |

## Image tags

| Tag | Meaning | Use for |
| --- | --- | --- |
| **`:<version>`** | immutable release | production (pin this) |
| **`:X.Y`** | newest patch release of that minor; moves on every release of it | tracking a minor |
| **`:latest`** | newest release | quickstarts |
| **`:rc`** | floating, rebuilt on every merge to main | tracking development, at your own risk |

## Open questions

- The `503` bodies were not captured: the local run had no receiver whose socket could die, and no ClickHouse that kept the startup wait open.
