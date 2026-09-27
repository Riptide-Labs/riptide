---
title: Respond to riptide alerts
sidebar_position: 7
description: One runbook per riptide alert in riptide-alerts.yml, with the query that confirms it, what each finding means, and which setting to change.
---

# Respond to riptide alerts

Each section is the runbook an alert's `runbook_url` opens.
To set up the scrape, the rules and the dashboards, see [Monitor riptide with Prometheus](../guides/monitor-riptide.md).

Every check runs against the Prometheus that loads **`riptide-alerts.yml`**; set **`PROM`** to its address.
The checks read the recording rules `riptide:*`, which carry a `stage` and a `component` label, and print each collector by its `collector` label, or by its scrape address where the target has none.
The healthy outputs were captured on macOS, which has no per-socket kernel counters: on Linux every listener adds a `listener <name>` line to the loss check.
**Riptide - Health** shows the same signals on one screen, and **Riptide - Stage detail** shows one stage against the JVM.

| Alert | Severity | Fires when | Threshold |
| --- | --- | --- | --- |
| [**`RiptideDown`**](#riptidedown) | critical | a riptide target is not scraped for 2 minutes | fixed |
| [**`RiptideDataLoss`**](#riptidedataloss) | critical | any stage counts flows that did not reach the flows table, for 5 minutes | fixed |
| [**`RiptideQueueFilling`**](#riptidequeuefilling) | warning | a parser dispatch or batch writer queue is over 80% full for 10 minutes | measured: fires after the loss has started |
| [**`RiptideWorkerSaturated`**](#riptideworkersaturated) | warning | a listener read loop or the batch flusher is over 80% busy for 15 minutes | measured for the flusher: leads loss |
| [**`RiptideHeapPressure`**](#riptideheappressure) | warning | heap used is over 90% of the maximum for 15 minutes | default |
| [**`RiptideGcPressure`**](#riptidegcpressure) | warning | over 10% of the time is spent in GC pauses for 10 minutes | default |
| [**`RiptideCpuPressure`**](#riptidecpupressure) | warning | CPU used is over 85% of the available cores for 15 minutes | default |
| [**`RiptideFileDescriptors`**](#riptidefiledescriptors) | warning | open files are over 80% of the limit for 10 minutes | default |
| [**`RiptideReloadFailing`**](#riptidereloadfailing) | warning | a configuration, inventory or classification reload failed in the last 15 minutes | fixed |

A default threshold is a starting value that flow load on the benchmark rig does not reach, so it was never measured against riptide; tune it to your hosts.
"Measured" means the [benchmark rig run](#what-the-benchmark-rig-showed) below.

## RiptideDown {/* #riptidedown */}

Prometheus has not scraped a riptide instance's **`/metrics`** for 2 minutes.
Nothing on that instance is being counted, and flows sent to it may not be stored.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/targets?state=active" \
  | jq -r '.data.activeTargets[] | select(.labels.job == "riptide") | "\(.labels.collector // .labels.instance) \(.labels.instance) \(.health)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host host.docker.internal:8080 up
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `curl -s http://<host>:8080/livez` does not answer | riptide is not running | Start it: `systemctl start riptide` for a package, `docker compose up -d` for the compose stack; read the last log lines for the cause |
| `/livez` answers on the host, the target stays `down` | Prometheus cannot reach the management port | Check the scrape address, firewalls and **`riptide.management.bind-address`**; the port is **`riptide.management.port`**, `8080` by default |
| `/metrics` returns `404` | **`riptide.management.metrics-enabled`** is `false` | Set it to `true` and restart |
| The target shows `host.docker.internal:8080` or `riptide:8080` and riptide runs elsewhere | the compose stack scrapes the wrong targets directory | Start the stack with the right **`PROMETHEUS_TARGETS`**, see [the compose guide](../guides/docker-compose.md) |

## RiptideDataLoss {/* #riptidedataloss */}

Flows did not reach the flows table for 5 minutes.
The `stage` and `component` labels say where.
For `batch-writer`/`failedRows` the count includes rows kept in `flows_dead_letter`, which can be replayed.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=sum by (collector, instance, stage, component) (increase(riptide:lost_total[5m]))' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host parser-dispatch flows:ipfix 0
riptide-on-host parser-dispatch flows:netflow5 0
riptide-on-host parser-dispatch flows:netflow9 0
riptide-on-host parser-dispatch flows:sflow 0
riptide-on-host pipeline dispatchErrors 0
riptide-on-host batch-writer droppedRows 0
riptide-on-host batch-writer failedRows 0
```

Any value above 0 names a stage that counted flows it could not store in the last 5 minutes.

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `listener <name>` | the kernel dropped datagrams because the socket receive buffer was full | Raise **`net.core.rmem_max`** for more burst room; if [RiptideWorkerSaturated](#riptideworkersaturated) fires for the same listener, the read loop is the limit, so split exporters across more receivers |
| `parser-dispatch <name>` | the dispatch queue was full: enrichment or persistence fell behind | Open **Riptide - Stage detail** for `parser-dispatch`; if the batch writer is saturated too, fix that first, otherwise give riptide more cores, since it runs one dispatch worker per core |
| `pipeline dispatchErrors` | enrichment or persistence threw | Search the log for the WARN lines around the start of the loss |
| `batch-writer droppedRows` | the batch writer queue was full | The flusher is the limit: see [RiptideWorkerSaturated](#riptideworkersaturated) |
| `batch-writer failedRows` | ClickHouse refused or did not answer inserts | Compare with `persister_batch_deadLetteredRows`: rows kept there are recoverable, see [Inspect and replay dead letters](dead-letters.md); then fix ClickHouse, whose own logs name the refusal |
| `batch-writer droppedRows` while the flusher is far below 0.8 | exporters send in bursts larger than the queue | Raise **`riptide.clickhouse.batch.queue-capacity`** to hold one export interval of rows at peak: the default 40,000 rows is about 3 s at 12,000 flows/s |

When several stages lose at once, start from the one whose alert fired first.
A full batch writer queue stalls the dispatch workers, a full dispatch queue stalls the listener's read loop, and the kernel then drops at the socket: on the rig, parser-dispatch followed the batch writer 45 s later and the listener 6 minutes later.
**Riptide - Health** shows the order in **When was each stage saturated?**; **Which stage is closest to full?** reads 100% for all of them.

## RiptideQueueFilling {/* #riptidequeuefilling */}

A bounded queue has held over 80% of its capacity for 10 minutes; at 100% it drops.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=riptide:queue_depth / riptide:queue_capacity' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host batch-writer queue 0
riptide-on-host parser-dispatch flows:ipfix 0
riptide-on-host parser-dispatch flows:netflow5 0
riptide-on-host parser-dispatch flows:netflow9 0
riptide-on-host parser-dispatch flows:sflow 0
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `batch-writer queue` above 0.8 | the flusher cannot insert as fast as flows arrive | See [RiptideWorkerSaturated](#riptideworkersaturated); the queue is not the early warning here, because 40,000 rows cover about 3.4 s at the measured 11.8k rows/s |
| `parser-dispatch <name>` above 0.8 | the dispatch workers are behind, or the batch writer behind them is full | If the batch writer is also full, fix that first; otherwise give riptide more cores; the queue holds 4,096 packets and its size is not a setting |

This alert confirms rather than warns.
Measured on the rig, the batch writer queue went over 80% 30 s before rows dropped, so with its 10-minute hold the alert fired after the loss had started; the dispatch queue sat at 0 in every scrape below the ceiling and went from 0 to full within 45 s above it.

## RiptideWorkerSaturated {/* #riptideworkersaturated */}

A single-threaded worker has been busy over 80% of the time for 15 minutes; at 100% the stage behind it drops.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=rate(riptide:busy_seconds_total[5m])' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host batch-writer flusher 0.026
riptide-on-host listener flows 0.005
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `batch-writer flusher` above 0.8, insert p99 rising | ClickHouse is slowing down | Open **Riptide - Stage detail** for `batch-writer` and fix ClickHouse; riptide settings will not help |
| `batch-writer flusher` above 0.8, insert time flat | the flusher issues many small inserts | Raise **`riptide.clickhouse.batch.max-rows`** for fewer, larger inserts, with two limits: a batch only grows past `max-rows` when rows arrive faster than `max-rows` per **`riptide.clickhouse.batch.max-latency`**, and **`riptide.clickhouse.batch.queue-capacity`** must stay several batches deep, or rows drop while an insert runs |
| `listener <name>` above 0.8, dispatch queue full | the read loop waits for room in the dispatch queue, which counts as busy | Fix the stage behind it first; see [RiptideDataLoss](#riptidedataloss) on how overload spreads back |
| `listener <name>` above 0.8, dispatch queue empty | one read loop parses everything that arrives on that port | Split exporters across more receivers on separate ports, or give riptide faster cores; the figure leaves out socket reads, so the real share is a little higher |

Measured on the rig, the flusher sat at 0.85 to 0.88 without losing a row and this alert fired 15 minutes later; the flusher's loss started only when offered load went past its ceiling.
The listener figure reached 0.88 only while it waited on a full dispatch queue; the rig never drove a read loop that hard by parsing alone, so its 0.8 is not validated for that case.

## RiptideHeapPressure {/* #riptideheappressure */}

Heap in use has stayed over 90% of the maximum for 15 minutes.
Sustained, this ends in long GC pauses or an `OutOfMemoryError`.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=jvm_heap_used{job="riptide"} / jvm_heap_max{job="riptide"}' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host 0.004
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| Above 0.9 and [RiptideGcPressure](#riptidegcpressure) fires too | the heap is too small for the load | Raise `-Xmx` in **`JAVA_OPTS`** (**`/etc/riptide/riptide.env`** for a package) and restart |
| Above 0.9 while GC time is low | used heap includes garbage not collected yet, and collections are reclaiming it cheaply | Watch GC time; act only when it rises |

## RiptideGcPressure {/* #riptidegcpressure */}

The application has spent over 10% of its time stopped for garbage collection for 10 minutes.
Every stage stops with it, so this shows up as saturation everywhere at once.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=rate(jvm_gc_seconds{job="riptide"}[5m])' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host 0.001
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| Above 0.1 with heap near its maximum | too little heap | Raise `-Xmx`, as for [RiptideHeapPressure](#riptideheappressure) |
| Above 0.1 with heap well below its maximum | allocation rate is high for the collector's settings | Collect a profile with [continuous profiling](profiling.md) before changing collectors |

## RiptideCpuPressure {/* #riptidecpupressure */}

The process has used over 85% of its available cores for 15 minutes.
The available cores honour a container's CPU limit.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=rate(jvm_cpu_processSeconds{job="riptide"}[5m]) / jvm_cpu_availableProcessors{job="riptide"}' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host 0.004
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| Above 0.85 with a saturated stage | offered load exceeds what the host can process | Raise the CPU limit or move exporters to another collector |
| Above 0.85 with every stage idle | CPU goes somewhere other than the flow path | Collect a profile with [continuous profiling](profiling.md) |

## RiptideFileDescriptors {/* #riptidefiledescriptors */}

Open file descriptors have stayed over 80% of the limit for 10 minutes.
At the limit riptide cannot open sockets or files.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=process_openFds{job="riptide"} / process_maxFds{job="riptide"}' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host 0
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| Above 0.8 and rising steadily | a leak | Record `ls -l /proc/<pid>/fd` twice an hour apart and open an issue with both |
| Above 0.8 and flat | the limit is low for the number of receivers and connections | Raise **`LimitNOFILE`** with a systemd drop-in for `riptide.service`, or the container's `nofile` ulimit |

## RiptideReloadFailing {/* #riptidereloadfailing */}

A configuration, inventory or classification reload failed in the last 15 minutes.
The previous version keeps serving, so nothing is lost, but the change you made is not in effect.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=increase(riptide:reload_failures_total[15m])' \
  | jq -r '.data.result[] | "\(.metric.collector // .metric.instance) \(.metric.component) \(.value[1] | tonumber | round)"'
```

Healthy output, captured from the compose stack with one riptide, collector `riptide-on-host`, running on a macOS host:

```text
riptide-on-host classification 0
riptide-on-host config 0
riptide-on-host inventory 0
```

A `0` for every component is healthy.

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `config` above 0 | the changed `config.yaml` failed validation | The WARN line in the log names the setting; fix the file, the next poll picks it up, see [Enable configuration hot reload](hot-reload.md) |
| `inventory` above 0 | the inventory file or the discovery endpoint was refused or unreachable | As above; with discovery on, every failing poll counts |
| `classification` above 0 | the rules could not be fetched or loaded | See [Write a classification rule](classification-rules.md) for the rules file format |

## What the benchmark rig showed {/* #what-the-benchmark-rig-showed */}

Run on 2026-09-27 against the integration of the golden-signals series, alerts and dashboards ([#902](https://github.com/Riptide-Labs/riptide/issues/902)), one riptide on a 4 vCPU virtual machine with 6 GiB heap, nl6 exporting NetFlow v9, ClickHouse 26.7 on the host.

| Load | Stored | Flusher busy | Loss | Alerts |
| --- | --- | --- | --- | --- |
| up to 16,630 flows/s, the most the generator delivered | all of it | 16% | none | none |
| 12,185 flows/s, inserts delayed 300 ms, 22 minutes | 12,170 rows/s | 0.88 | none | **RiptideWorkerSaturated** for the flusher, 15 minutes after it went pending |
| about 16,000 flows/s, inserts delayed 300 ms, 17 minutes | 13,558 rows/s, the ceiling | 0.999 | batch-writer 648/s, then parser-dispatch 662/s, then listener | **RiptideDataLoss** for the three stages, **RiptideQueueFilling** after the loss |

The 300 ms delay on inserts stands in for a ClickHouse that cannot keep up: without it the batch writer had no reachable ceiling on this rig.
The batch writer queue was raised to 400,000 rows for the delayed runs: at the default 40,000 the generator's synchronised 5 s exports overflowed it at about 12,000 flows/s with the flusher 5% busy, and no warning alert fired before **RiptideDataLoss**.

