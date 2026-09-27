---
title: Respond to riptide alerts
sidebar_position: 7
description: One runbook per riptide alert in riptide-alerts.yml, with the query that confirms it, what each finding means, and which setting to change.
---

# Respond to riptide alerts

Each section is the runbook an alert's `runbook_url` opens.
To set up the scrape, the rules and the dashboards, see [Monitor riptide with Prometheus](../guides/monitor-riptide.md).

Every check runs against the Prometheus that loads **`riptide-alerts.yml`**; set **`PROM`** to its address.
The checks read the recording rules `riptide:*`, which carry a `stage` and a `component` label.
**Riptide - Health** shows the same signals on one screen, and **Riptide - Stage detail** shows one stage against the JVM.

| Alert | Severity | Fires when | Threshold |
| --- | --- | --- | --- |
| [**`RiptideDown`**](#riptidedown) | critical | a riptide target is not scraped for 2 minutes | fixed |
| [**`RiptideDataLoss`**](#riptidedataloss) | critical | any stage counts flows that did not reach the flows table, for 5 minutes | fixed |
| [**`RiptideQueueFilling`**](#riptidequeuefilling) | warning | a parser dispatch or batch writer queue is over 80% full for 10 minutes | not yet checked on the rig |
| [**`RiptideWorkerSaturated`**](#riptideworkersaturated) | warning | a listener read loop or the batch flusher is over 80% busy for 15 minutes | not yet checked on the rig |
| [**`RiptideHeapPressure`**](#riptideheappressure) | warning | heap used is over 90% of the maximum for 15 minutes | default |
| [**`RiptideGcPressure`**](#riptidegcpressure) | warning | over 10% of the time is spent in GC pauses for 10 minutes | default |
| [**`RiptideCpuPressure`**](#riptidecpupressure) | warning | CPU used is over 85% of the available cores for 15 minutes | default |
| [**`RiptideFileDescriptors`**](#riptidefiledescriptors) | warning | open files are over 80% of the limit for 10 minutes | default |
| [**`RiptideReloadFailing`**](#riptidereloadfailing) | warning | a configuration, inventory or classification reload failed in the last 15 minutes | fixed |

A default threshold is a starting value that flow load on the benchmark rig does not reach, so it was never measured against riptide; tune it to your hosts.

## RiptideDown {/* #riptidedown */}

Prometheus has not scraped a riptide instance's **`/metrics`** for 2 minutes.
Nothing on that instance is being counted, and flows sent to it may not be stored.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/targets?state=active" \
  | jq -r '.data.activeTargets[] | select(.labels.job == "riptide") | "\(.labels.instance) \(.health)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
host.docker.internal:8080 up
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
curl -s "$PROM/api/v1/query" --data-urlencode 'query=sum by (stage, component) (increase(riptide:lost_total[5m]))' \
  | jq -r '.data.result[] | "\(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
parser-dispatch flows:ipfix 0
parser-dispatch flows:netflow5 0
parser-dispatch flows:netflow9 0
parser-dispatch flows:sflow 0
pipeline dispatchErrors 0
batch-writer droppedRows 0
batch-writer failedRows 0
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

## RiptideQueueFilling {/* #riptidequeuefilling */}

A bounded queue has held over 80% of its capacity for 10 minutes; at 100% it drops.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=riptide:queue_depth / riptide:queue_capacity' \
  | jq -r '.data.result[] | "\(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
batch-writer queue 0
parser-dispatch flows:ipfix 0
parser-dispatch flows:netflow5 0
parser-dispatch flows:netflow9 0
parser-dispatch flows:sflow 0
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `batch-writer queue` above 0.8 | the flusher cannot insert as fast as flows arrive | See [RiptideWorkerSaturated](#riptideworkersaturated); the queue is not the early warning here, because 40,000 rows cover about 3.4 s at the measured 11.8k rows/s |
| `parser-dispatch <name>` above 0.8 | the dispatch workers are behind | Give riptide more cores; the queue holds 4,096 packets and its size is not a setting |

A 4,096-slot dispatch queue can go from empty to full within one scrape, so this alert may only confirm what [RiptideDataLoss](#riptidedataloss) already reports.

## RiptideWorkerSaturated {/* #riptideworkersaturated */}

A single-threaded worker has been busy over 80% of the time for 15 minutes; at 100% the stage behind it drops.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=rate(riptide:busy_seconds_total[5m])' \
  | jq -r '.data.result[] | "\(.metric.stage) \(.metric.component) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
batch-writer flusher 0.018
listener flows 0.003
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `batch-writer flusher` above 0.8 | ClickHouse inserts take most of the flusher's time | Open **Riptide - Stage detail** for `batch-writer`: a rising insert p99 points at ClickHouse; flat insert time means more batches, so raise **`riptide.clickhouse.batch.max-rows`** for fewer, larger inserts |
| `listener <name>` above 0.8 | one read loop parses everything that arrives on that port | Split exporters across more receivers on separate ports, or give riptide faster cores; the figure leaves out socket reads, so the real share is a little higher |

## RiptideHeapPressure {/* #riptideheappressure */}

Heap in use has stayed over 90% of the maximum for 15 minutes.
Sustained, this ends in long GC pauses or an `OutOfMemoryError`.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=jvm_heap_used{job="riptide"} / jvm_heap_max{job="riptide"}' \
  | jq -r '.data.result[] | "\(.metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
host.docker.internal:8080 0.009
```

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| Above 0.9 and [RiptideGcPressure](#riptidegcpressure) fires too | the heap is too small for the load | Raise `-Xmx` in **`JAVA_OPTS`** (**`/etc/riptide/riptide.env`** for a package) and restart |
| Above 0.9 while GC time is low | the heap is full of live data between collections, normal for a large heap under steady load | Watch GC time; act only if it rises |

## RiptideGcPressure {/* #riptidegcpressure */}

The application has spent over 10% of its time stopped for garbage collection for 10 minutes.
Every stage stops with it, so this shows up as saturation everywhere at once.

### Check

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/query" --data-urlencode 'query=rate(jvm_gc_seconds{job="riptide"}[5m])' \
  | jq -r '.data.result[] | "\(.metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
host.docker.internal:8080 0.001
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
  | jq -r '.data.result[] | "\(.metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
host.docker.internal:8080 0.006
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
  | jq -r '.data.result[] | "\(.metric.instance) \(.value[1] | tonumber * 1000 | round / 1000)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
host.docker.internal:8080 0
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
  | jq -r '.data.result[] | "\(.metric.component) \(.value[1] | tonumber | round)"'
```

Healthy output, from the compose stack with one riptide running on the host:

```text
classification 0
config 0
inventory 0
```

A `0` for every component is healthy.

### Diagnose

| Finding | Likely cause | Fix |
| --- | --- | --- |
| `config` above 0 | the changed `config.yaml` failed validation | The WARN line in the log names the setting; fix the file, the next poll picks it up, see [Enable configuration hot reload](hot-reload.md) |
| `inventory` above 0 | the inventory file or the discovery endpoint was refused or unreachable | As above; with discovery on, every failing poll counts |
| `classification` above 0 | the rules could not be fetched or loaded | See [Write a classification rule](classification-rules.md) for the rules file format |
