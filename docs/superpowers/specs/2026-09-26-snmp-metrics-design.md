# SNMP interface metrics beside flows

Status: draft for review.
Date: 2026-09-26.
Decision: approach A, VictoriaMetrics through Prometheus remote-write, poller inside riptide-flows.

## Intent

Riptide collects SNMP interface counters next to the flows it already stores, so an operator can see true link utilization, reconcile flow bytes against counters, alert on link health and plan capacity from the same fleet.
Interface counters ship first.
The design must not block arbitrary MIB collection later, but nothing beyond interface counters is designed here.

Target scale is about 100,000 devices and 5 million interfaces at a 60 second interval.
Consumers are Grafana dashboards, the riptide MCP tools joined with flow data, alerting, and multi-year capacity planning.
The deployment already runs a Prometheus-compatible time series database, and riptide may write to it.

Success means: a tagged device that sends no flows has `ifHCInOctets` queryable in PromQL within one interval of being added to the inventory, a flow exporter has the same series without any extra configuration, and a collector at the design load stays within its walk budget on the benchmark rig.

## What exists today

SNMP in riptide is metadata only.
`InterfaceSnapshotPoller` walks ifTable and ifXTable (ifName, ifAlias, ifHighSpeed) on a bounded pool and serves enrichment from the stored snapshot.
Nothing is stored as a time series.
The MCP interface utilization tool reports flow-derived utilization against the SNMP interface speed; it has no counter source.

An exporter is registered for polling when its first flow arrives and is dropped after a configurable number of silent intervals.
The poller caps concurrency with `poolWidth` (default 4) and registrations with `maxExporters` (default 4,096).
The `snmp.agents` inventory tree holds CIDR ranges with credentials and a polling profile.
The `exporters` tree holds named devices and is owned by discovery when discovery is on.

Persistence is ClickHouse with per-tenant hard isolation and four 1-minute rollups.
The management endpoint already exposes riptide's own metrics in Prometheus text format.

## Store decision

Both candidate stores carry the design volume.
The decision rests on query language, alerting, tenancy and operating cost.

| Concern | VictoriaMetrics via remote-write | ClickHouse table beside `flows` |
| --- | --- | --- |
| Counter rate and reset handling | `rate()` in PromQL, free | hand-written SQL window functions per panel |
| Alerting | vmalert or Grafana unified alerting on PromQL | Grafana SQL alerts, weak at 5M interfaces |
| Long-term capacity planning | recording rules into a long-retention instance | rollup materialized views, same pattern as flows |
| Join with flows | second client in MCP tools, combined in Java | native SQL join |
| Tenancy | cluster `accountID:projectID` in the write path | existing `CHECK` barrier |
| Operating cost | store already exists per deployment | second heavy insert stream firing the flow rollup views |

Three of the four consumers live in the PromQL ecosystem and the store already exists.
Riptide writes counters to VictoriaMetrics.
A narrow ClickHouse mirror of per-interface octet deltas is not designed; it becomes a decision only if measured MCP join latency over HTTP is unacceptable.

## Scale envelope

These figures are derived from the interface set and published per-store numbers.
They are not measured.
The benchmark in the testing section is what can falsify them, and it runs before phase 2.

| Quantity | Value |
| --- | --- |
| Interface set | 14 counter and gauge series plus one info series |
| Active series | about 75 million |
| Samples per second | about 1.25 million |
| SNMP PDUs per second fleet-wide, GETBULK, about 50 interfaces per device | about 10,000 to 20,000 |
| Storage at full resolution | about 80 to 150 GB per day |

## Components

| Component | Responsibility | Talks to |
| --- | --- | --- |
| `InterfaceSnapshotPoller` | Registers devices, schedules and runs walks, refreshes enrichment snapshots, emits samples | `SnmpService`, `MetricSink`, inventory |
| `CollectionDefinition` | Declares which OIDs a walk reads and how rows become samples | Read by the poller |
| `MetricSink` | Bounded queue and background flusher for samples | `PrometheusRemoteWriteSink` |
| `PrometheusRemoteWriteSink` | Encodes batches as remote-write 1.0 and POSTs them | The configured remote-write URL |
| `MetricsQueryClient` | Reads counter rates for the MCP tools | The configured query URL |
| Discovery composer | Sets `poll` on composed exporter entries from a NetBox tag | Inventory |

## Poller

### One walk serves both purposes

When a polling profile lists a collection, the walk reads the collection's columns and the three enrichment columns in one GETBULK pass.
The enrichment snapshot is refreshed from the same rows.
There is no second schedule, registration or in-flight flag.
A profile with no collection keeps today's cadence and today's column set.

### The polling profile decides

`PollingProfile` gains `collect`, a list of collection definition names, default empty.
When `collect` is non-empty, `refreshInterval` is the counter interval.
The walk budget becomes 80 percent of the interval instead of the fixed two minutes.
A walk that outlives its interval is cancelled and counted as failed, because a slow success that overlaps the next walk is a failure.

### Two registration sources

A registration carries a `source`: `flow-arrival` or `inventory`.

Flow-arrival registrations keep today's lifecycle.
The first flow registers the exporter and silence for the configured number of intervals removes it.

Inventory registrations come from exporter entries marked `poll: always`.
The poller registers them in its inventory sweep, which runs at startup and after every reload.
Silence never removes them.
They are removed when the inventory drops the entry.

After registration the two sources share everything: the address-derived offset within the interval, the per-cycle jitter, the walk permits, the budget, the dead-endpoint backoff and the sink.
The only code that reads `source` is the reload path and the silence sweep.

### The `poll` switch is on the entry

An exporters-tree entry gains `poll: on-flow | always`, default `on-flow`.
An entry with `poll: always` needs no observation domain.
The switch lives on the entry rather than the profile because "poll me without flows" is a fact about one device, while a profile is shared by every device in a range.

Phase 1 sets `poll: always` from discovery through the `__meta_netbox_tags` label, matched against the tag named by `riptide.discovery.poll-always-tag`.
`netbox-api` emits that label from a device's tag slugs.
Any source whose document carries the same label, such as a `prometheus-sd` document, is honoured the same way, because the renderer reads the label, not the source.
`mapped-json` never carries that label, so its entries always default to `on-flow` in phase 1.
Entries in the inventory file can carry `poll: always` directly when discovery is off; with discovery on, discovery owns the exporters tree and the file cannot add entries to it.

### Sharding

Flow-arrival registrations shard by load-balancer stickiness.
NetFlow templates are learned per collector, so the deployment already needs source-address stickiness; the poller inherits that partition.

Inventory registrations shard by inventory partition.
Each collector's `riptide.discovery.filter` names its shard, for example `tag=riptide-shard-a`, and the composed inventory is the collector's slice.
Moving a device between shards is a tag edit in NetBox, picked up on the next discovery poll of both collectors.

No per-collector label goes on any series.
A failover of a flow exporter from one collector to another therefore continues the same series after a gap of about one interval plus template learning.

Overlap is safe when the store deduplicates.
Two collectors polling the same device produce duplicate samples; VictoriaMetrics collapses them only with `-dedup.minScrapeInterval` set, and the deployment guide states that.
The cost is double agent load.
Nothing in this design relies on that property, but it means "when in doubt, poll" is the right lean.

### Known limit: a dead collector leaves a gap

A collector that dies leaves its inventory-registered slice unpolled until it returns or the operator retags the devices.
The detection is an `absent_over_time` alert in the shipped rule set.
High-availability pairs polling the same slice and a coordination store that reassigns devices are both explicitly not designed.

### Walk execution

Walks are asynchronous.
A walk takes a permit when it starts and returns it when its future completes, so no thread waits on an agent for it.
The one exception is an SNMPv3 walk whose session does not yet know the agent's engine ID: its discovery is synchronous and blocks for up to one second.
On the shared collect session the engine ID is cached after the first discovery and evicted after any failed walk, so discovery runs on the first walk and again on the walk after a failure.
The eviction is what lets a device whose engine ID changed be walked again, because snmp4j keeps the cached value when the unknown-engine-ID report arrives.
Enrichment-only walks open a fresh session each time and discover on every walk.
Riptide's side of a walk runs on a small `snmp-walk-io` executor, as wide as `poolWidth`, never on the scheduler tick thread or on snmp4j's threads.
An endpoint whose last walk failed draws from a separate suspect bulkhead, `suspect-pool-width` permits, so dead agents waiting out their timeouts cannot hold the permits healthy endpoints need.

### Pool and guards

`poolWidth` stays the fleet-wide concurrency bound for endpoints in good standing.
Its default stays 4.
The reference page gives the sizing rule: permits needed is about walks per second times walk latency, and the suspect budget is separate.
Whether snmp4j sustains the design PDU rate in one JVM is unmeasured and is the first question the benchmark answers.

`maxExporters` also caps inventory registrations.
The inventory loader is pure and does not read the cap.
The poller applies it in its inventory sweep.
When the `poll: always` entries alone exceed the cap, none of them is registered, and the sweep logs an error with the count.
When they fit but flow-arrival registrations already fill the cap, each entry that finds no room is refused on its own.
Those entries are counted on the `snmp.poller.inventoryRefused` gauge, and the sweep logs one warning with the count.
The flow-arrival path keeps its quiet rejection, counted on `snmp.poller.rejectedLookups`, because that cap protects memory against a key that comes off the wire.
An inventory is authored, so silently polling a prefix of it would be a defect an operator finds weeks later on a dashboard.

## Collection definitions

A `CollectionDefinition` is a record: name, table base OID, index label name, and a list of columns.
Each column has an OID, a metric name and a type: `counter64`, `gauge` or `info`.
One built-in definition ships, `if-mib-interfaces`, covering ifHCInOctets, ifHCOutOctets, ifHCInUcastPkts, ifHCOutUcastPkts, ifHCInMulticastPkts, ifHCOutMulticastPkts, ifHCInBroadcastPkts, ifHCOutBroadcastPkts, ifInErrors, ifOutErrors, ifInDiscards, ifOutDiscards, ifOperStatus and ifAdminStatus, plus the info columns ifName, ifAlias and ifHighSpeed.
OIDs are numeric.
There is no MIB compiler.

A definition loaded from YAML later is the same record from a different loader.
Arbitrary MIB collection is therefore a loader, not a redesign, and it is not in this design.

Metric names follow snmp_exporter conventions: `ifHCInOctets{ifIndex="3", ifName="Gi0/0/3"}`.
Operators already own dashboards in that shape and the mapping to the MIB is one to one.
Riptide adds `riptide_interface_info{ifIndex, ifName, ifAlias, ifHighSpeed} 1`, so an alias edit changes the info series and not the counter series.

## Sink and label contract

`MetricSink` mirrors the ClickHouse persister: a bounded queue, one background flusher, and drop instead of block.
It reports `metrics.sink.queueDepth`, `droppedSamples`, `failedSamples` and `flush` on the existing `/metrics` endpoint.

One implementation ships, `PrometheusRemoteWriteSink`.
It encodes a batch as a remote-write 1.0 `WriteRequest`, compresses it with snappy and POSTs it.
It retries with backoff on 5xx, 429 or a connection failure, and drops with a count on any other non-2xx status.
The remote-write protobuf and the snappy block are hand-encoded, by `RemoteWriteEncoder` and `SnappyBlock`.
The only new dependencies are test scope: `protobuf-java` and `aircompressor-v3`, used as independent decoders of what those two classes produce.

Every series carries `tenant`, `organisation`, `zone`, `exporter` (the inventory name), `exporter_address` and the definition's index label.
The timestamp of every sample from one walk is that walk's start time.
Counters are pushed raw and monotonic.
The poller never computes deltas or masks counter resets; PromQL does that.

## Tenancy

One riptide process carries one tenant and one organisation, as it does for flows.
The remote-write URL is configured literally, so for a VictoriaMetrics cluster the operator writes the tenant into the path, `/insert/<accountID>:<projectID>/prometheus/api/v1/write`, and the store enforces isolation from the path.
The `tenant` and `organisation` labels stay stamped for a single-node store.
Grafana follows the existing posture: one org or instance per tenant, with the datasource routed through vmauth.

## Consumers

The MCP tools gain `MetricsQueryClient` against the store's `query_range` endpoint.
`InterfaceUtilizationTool` uses counter rate when the client is configured and falls back to flow-derived utilization otherwise, and its result says which it used.

Alerting and capacity planning are not riptide code.
Riptide ships example vmalert alert rules (utilization, errors, oper status, and `absent_over_time` for unpolled devices) and recording rules (5 minute and 1 hour per-interface rates for a long-retention instance) as an asset next to the Grafana dashboards, distributed the same way.

## Failure handling

| Failure | Behaviour |
| --- | --- |
| Walk fails or exceeds its budget | No samples are emitted for that cycle, so the gap is visible in PromQL. Never zeros. Dead-endpoint backoff applies unchanged. |
| Device has no ifXTable | No octet series and one rate-limited warning. There is no 32-bit fallback at this scale. |
| Store unreachable | Samples drain into the bounded queue, then drop with `droppedSamples` and a rate-limited log. |
| Store rejects a batch with 4xx | The batch is dropped and counted in `failedSamples`. There is no dead-letter table for samples. |
| Inventory exceeds `maxExporters` in `poll: always` entries | The poller's inventory sweep registers none of them and logs the count. The inventory itself still loads. |
| Flow registrations leave no room for some `poll: always` entries | Those entries are not registered. The sweep counts them on `snmp.poller.inventoryRefused` and logs one warning. |
| ifName changes on a device | The counter series churns once. Accepted. |

## Configuration

Every key names its consumer and the test that proves the read.

| Key | Type | Default | Consumer | Proving test |
| --- | --- | --- | --- | --- |
| `riptide.metrics.remote-write.url` | URL | unset, disables the sink | `PrometheusRemoteWriteSink` | sink IT asserts the POST path |
| `riptide.metrics.remote-write.bearer-token` | secret reference | unset | `PrometheusRemoteWriteSink` | sink IT asserts the `Authorization` header |
| `riptide.metrics.remote-write.batch.max-samples`, `.batch.max-latency`, `.queue-capacity` | int, duration, int | 5000, 2s, 2000000; the benchmark revises them | sink flusher | unit test on flush trigger and on drop when the queue is full |
| `riptide.metrics.query.url` | URL | unset, tools fall back to flow-derived figures | `MetricsQueryClient` | MCP tool test asserts the query is issued and the fallback is used when unset |
| polling profile `collect`, `refresh-interval` | list of strings, duration | empty, existing default | poller | poller test asserts the walk column set and cadence |
| exporters entry `poll` | `on-flow` or `always` | `on-flow` | poller registration | a silent `poll: always` device is walked within one interval; a silent `poll: on-flow` device is not |
| `riptide.discovery.poll-always-tag` | string | unset, no entry is marked | discovery composer, matched against the `__meta_netbox_tags` label (`netbox-api` emits it; a `prometheus-sd` document carrying it is honoured the same way) | composed document carries `poll: always` for a tagged device and `on-flow` for an untagged one |
| `riptide.discovery.filter` (existing) | string | unset | discovery source | already tested; the spec adds the documented use as the shard selector |

The existing outbound-tls key today reaches only the discovery endpoints and the classification ruleset URL.
This design extends its reach to the remote-write and query URLs, and the outbound TLS reference page is updated in the same change.

## Existing prose this change must reach

The `snmp-interface-polling` requirement rationale says that enumerating every configured node would poll devices that never send flows and is the opposite of the capability's purpose.
That was true while enrichment was the only purpose and is false now.
The rationale and the scenario "a silent exporter is never polled" are rewritten to apply to `poll: on-flow` entries only, and a new scenario pins the `poll: always` behaviour.
The `InterfaceSnapshotPoller` class javadoc describes flow arrival as the only registration path and is updated in the same commit.

## Testing

Unit tests cover the definition-to-samples mapping, the remote-write encoding round trip (encode, decode with protobuf, assert labels and timestamps), and the queue drop semantics with the sink stalled.

An integration test under `-Pe2e`, `SnmpMetricsIT`, runs one snmp4j-agent (already in the pom at test scope) serving ifXTable against a VictoriaMetrics testcontainer.
The agent sends no flows and is registered through `poll: always`.
The test asserts that `rate(ifHCInOctets[10s])` returns the injected slope and that `riptide_interface_info` carries the agent's alias.
The flow-arrival registration path is pinned by unit tests, not by this IT.

A scale benchmark on the rig runs a simulated agent fleet against one collector.
It measures PDUs per second per JVM, the walk latency distribution at 5,000 devices per collector, the store's ingest rate and its memory per active series.
It is the only thing that can falsify the scale envelope above, and it runs after phase 1 and before phase 2.

## Prerequisites and out of scope

The inventory ceiling, an O(n) node lookup per flow and minutes of Spring binding at 10,000 nodes, must be fixed before this design is deployed at scale.
That is a separate issue.

Source-address stickiness at the UDP load balancer is documented as a deployment requirement, not enforced by riptide.

Out of scope: a ClickHouse mirror of counters, YAML-loaded collection definitions, a `poll-always-tag` path for `mapped-json` sources (no label exists there to key it on), high-availability polling pairs, a coordination store, and a 32-bit counter fallback.

## Rollout

Phase 1: collection definitions, poller extension with both registration sources, `poll` on entries and the NetBox tag mapping, the remote-write sink, and the integration test.
Then the scale benchmark.
Phase 2: `MetricsQueryClient` and the MCP tool join.
Phase 3: vmalert and recording rule assets beside the dashboards.
