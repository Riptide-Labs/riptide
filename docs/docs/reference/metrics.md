---
title: Metrics reference
sidebar_position: 14
description: Every metric riptide registers, by registry name, with its type, what it counts and what to alert on.
---

# Metrics reference

Names below are the registry's dotted names.
At `GET /metrics` every character outside `[a-zA-Z0-9_:]` becomes `_`, so `config.reload.stale` is scraped as `config_reload_stale`; the exposition rules are in [Management endpoints and ports](management.md#metrics-endpoint).
Types: a counter only rises, a meter is a counter with `_rate_1m` and `_rate_5m` gauges beside it, a gauge is a current reading, a timer is a summary in seconds.

## Configuration and inventory reload

Registered only while `riptide.config.reload-interval` is set.
Semantics are in [How configuration reloads work](../architecture/reloading.md).

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`config.reload.successes`** | counter | a changed `config.yaml` validated and committed | absence of increments while edits are expected |
| **`config.reload.failures`** | counter | a changed `config.yaml` failed validation; the running configuration is kept | `> 0` |
| **`config.reload.partial`** | counter | the config committed but the inventory rebuild against it is still pending; a subset of `successes`, counted once per edit | not an alert on its own |
| **`config.reload.stale`** | gauge | 1 while the last file that could be read did not commit; a skipped cycle does not recompute it | `== 1`, and on absence if hot reload is mandatory |
| **`config.reload.dead`** | gauge | 1 when the poll schedule stopped and will not run again, including after a deliberate shutdown | `> 0` on processes expected to be up |
| **`inventory.reload.successes`** | counter | as above, for the inventory file; shared with the discovery watcher | as above |
| **`inventory.reload.failures`** | counter | as above; with discovery on, a refused or unreachable poll counts here on every poll | `> 0` |
| **`inventory.reload.stale`** | gauge | as above; with discovery on, the endpoint's document differs from what is serving | `== 1` |
| **`inventory.reload.dead`** | gauge | as above | `> 0` |

## Classification rules

`classification.reload.stale`, `classification.rules.*` are registered whether or not `riptide.classification.reload-interval` is set; `classification.reload.dead` only with an interval.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`classification.reload.successes`** | counter | loads that published a ruleset; a healthy start leaves this at 1 | absence of increments while edits are expected |
| **`classification.reload.failures`** | counter | reloads that did not happen: a fetch that failed, or a load that threw; not latched, every failing attempt counts again | `> 0` |
| **`classification.reload.stale`** | gauge | 1 when the last fetch or load attempt failed and no later one has succeeded; a skipped cycle does not recompute it | `== 1` |
| **`classification.reload.dead`** | gauge | 1 if the poll schedule stopped and will never run again, including after a deliberate shutdown | `> 0` on processes expected to be up |
| **`classification.rules.rejected`** | gauge | rules in the serving ruleset that classify nothing because the engine could not use them; `-1` means no ruleset has ever been published | `> 0` |
| **`classification.rules.published`** | gauge | rules in the serving ruleset, rejected ones included; `-1` on the same condition | not an alert |
| **`classification.rules.preprocessed`** | gauge | the same ruleset counted the way the tree build works on it, reversed rules included, roughly double the row count for an omnidirectional ruleset; `-1` on the same condition. This is the number the [size bound](../architecture/classification-build-cost.md) is about | `> 25000` |
| **`reload`** | timer | the whole classification reload: resource read, preprocessing and build | not an alert |

## Ingest loss {/* #ingest-loss */}

What each of these counts, and the delivery arithmetic between them, is in [Where flows can be lost](../architecture/loss-accounting.md).

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`listeners.<name>.socketDrops`** | gauge | datagrams the kernel discarded because the socket receive buffer was full; read from `/proc/net/udp`, Linux only, absent elsewhere | rising |
| **`parsers.<name>.undecodableSets`** | counter | Data Sets discarded because their IPFIX or NetFlow v9 Template was not known; counts Sets, not records, and includes Options Data Sets | sustained non-zero rate; a burst at startup is normal |
| **`parsers.<name>.dispatchQueueDepth`** | gauge | packets waiting to be enriched; registered while the parser runs | approaching 4096 |
| **`parsers.<name>.dispatchDrops`** | counter | records discarded because enrichment or persistence fell behind, or discarded at shutdown | `> 0` |
| **`parsers.<name>.unmodelledElementTemplates`** | counter | IPFIX templates announcing an information element riptide parses and then discards (today IE 390 to 399); not an error | the total, not a rate |
| **`parsers.<name>.recordsReceived`** | meter | records parsed | the base of the delivery arithmetic |
| **`parsers.<name>.recordsScheduled`** | meter | records handed to the dispatch queue; excludes queue-full drops | not an alert |
| **`parsers.<name>.recordsDispatched`** | meter | records the dispatcher returned from, errors included | not a delivery confirmation |
| **`pipeline.dispatchErrors`** | counter | records lost because enrichment or persistence threw; with batching off, a refused insert counts here | `> 0` |
| **`persister.batch.queueDepth`** | gauge | rows waiting to be inserted | approaching `riptide.clickhouse.batch.queue-capacity` |
| **`persister.batch.droppedRows`** | counter | rows the queue never handed to an insert: queue full, repository stopping, producer interrupted, offered after the shutdown drain; exact | sustained rate |
| **`persister.batch.failedRows`** | counter | rows an insert was attempted for and lost; charges the whole batch, so an upper bound for a refused insert | sustained rate, as a signal and not a loss figure |
| **`persister.batch.deadLetteredRows`** | counter | rows of a refused batch kept in `flows_dead_letter` instead of being dropped | not an alert; read with `failedRows` |
| **`persister.batch.deadLetterFailedRows`** | counter | rows of a refused batch that could not be kept either | any movement |
| **`persister.batch.batchSize`** | histogram | rows per flushed batch | not an alert |
| **`persister.batch.flush`** | timer | insert duration per batch | not an alert |
| **`logPersisting.persister`** | timer | the enqueue latency, the hand-off into the buffer, normally microseconds; not the insert duration | not an alert |

## Parser gauges

Registered while the parser runs and deregistered when it stops, so a stopped receiver publishes no series.
Only IPFIX and NetFlow v9 populate them; NetFlow v5 and sFlow pairs read 0.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`parsers.<name>.sessionCount`** | gauge | exporters, one per `(session, observation domain)` pair; eventually consistent with "holds at least one template" | absence, not a value |
| **`parsers.<name>.templateCount`** | gauge | templates held across all exporters; what drives the per-record cost of the parse path | absence, not a value |

## NetFlow v5 sampling rate resolution

Per packet and per receiver; each leaf name is the value written to that flow's `samplingProvenance`.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`parsers.<name>.samplingRate.header`** | meter | packets whose rate came from the exporter's header, including an explicit `1` | a `header` rate falling to zero on a fleet that used to advertise |
| **`parsers.<name>.samplingRate.fallback`** | meter | packets that fell through to the receiver's `flow-sampling-interval-fallback` | not an alert |
| **`parsers.<name>.samplingRate.assumed`** | meter | packets with no rate anywhere, recorded as `1` | rising on a fleet that samples |

### Sampling rate tables

Registry names are dotted; `/metrics` renders them with underscores (`parser_optionSampling_expired`).

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`parser.optionSampling.consumed`** | meter | Sampler options records stored as an exporter-wide rate. | not an alert |
| **`parser.optionSampling.skipped`** | meter | Sampler options records the table declined (no usable interval, an algorithm riptide cannot store). | a rise together with `parser.options.recognisedUnusable` |
| **`parser.optionSampling.expired`** | meter | Learned rates dropped 24 h after the exporter stopped advertising. Flows fall to the receiver fallback or `assumed`. | any sustained rate: an exporter is losing its learned rate |
| **`parser.optionSampling.evicted`** | meter | Rate entries displaced by table pressure rather than silence, live ones included. | `> 0`: the table is full |
| **`parser.optionSampling.resolved`** | meter | Flow lookups that found a learned rate. | not an alert |
| **`parser.optionSampling.unresolved`** | meter | Flow lookups that found none. | not an alert; compare against `resolved` |
| **`parser.selectorReport.consumed`** | meter | IPFIX Selector Reports stored per `selectorId`. | not an alert |
| **`parser.selectorReport.skipped`** | meter | Selector Reports declined (ratio not storable, parameters missing). | a rise together with `parser.options.recognisedUnusable` |
| **`parser.selectorReport.expired`** | meter | Selector entries dropped after 24 h of silence. Flows naming the Selector fall back to the exporter-wide rate (`derived` becomes `options`). | any sustained rate |
| **`parser.selectorReport.evicted`** | meter | Selector entries displaced by table pressure. | `> 0` |

### Option records at the tap

Collector-wide; nothing here names an exporter. `offered` always equals the sum of the other three.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`parser.options.offered`** | meter | Option data records seen. | not an alert |
| **`parser.options.claimed`** | meter | Records stored by at least one consumer. | not an alert |
| **`parser.options.recognisedUnusable`** | meter | Records a consumer understood and stored nothing from: an unusable `applicationId` or over-long name, an interface record with no `ifIndex`, an interval of `0` (a withdrawal), or a sampling algorithm riptide cannot store. | a climb; the per-consumer `_skipped` meter that moves with it names the consumer |
| **`parser.options.unrecognised`** | meter | Records no consumer knew the shape of (VRF tables, metering-process statistics). | a change in rate, not presence |

### Session state bounds

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`flows.session.sources`** | gauge | UDP sources currently holding a slot. | approaching `riptide.flows.session.max-sources` |
| **`flows.session.scopes`** | gauge | Admitted scope identities across all sources. | not an alert |
| **`flows.session.rejectedSources`** | meter | A new source was refused because `max-sources` was reached. | any rate on a healthy fleet: the bound is too low, or a spray is running |
| **`flows.session.rejectedScopes`** | meter | A source's least-recently-used scope was displaced because `max-scopes-per-source` was reached. | a steady rate |
| **`enrichment.optionInterfaces.rejected`** | meter | An interface entry was evicted because `max-ifindexes-per-scope` was reached. Degrades only. | a steady rate: raise the bound |
| **`enrichment.optionApplications.rejected`** | meter | An application-table entry was evicted because the fixed 16,384-id scope cap was reached. | a steady rate |

### Exporter application tables

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`enrichment.optionApplications.consumed`** | meter | Application-table option rows stored. | not an alert |
| **`enrichment.optionApplications.skipped`** | meter | A named row with no usable `applicationId`, an id of `0`, or a name longer than 64 characters. | a sustained rate on an exporter you expect names from |
| **`enrichment.optionApplications.rejected`** | meter | An entry evicted because a scope reached its fixed cap of 16,384 ids. | `> 0` on a healthy fleet: an exporter is sending more ids than a protocol pack holds |
| **`enrichment.application.unresolved`** | meter | A record carried a non-zero `applicationId` the exporter's table cannot name; the rules answered instead. Normal for the first table refresh interval after a restart, permanent on a device that exports ids without a table. | a rate that does not fall to zero after the exporter's table refresh interval |

### Discovery

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`discovery.targets`** | gauge | Exporter entries in the inventory currently serving. Never describes a candidate that was composed and then refused. | A drop below the fleet size you expect; safe to alert on because it only ever describes what serves. |
| **`discovery.skipped`** | gauge | Entries the most recent render dropped for want of a usable address (a prefix length, an unparsable value, a NetBox device with no primary IP), whether or not the candidate built from it was published. | `> 0` when every device should carry an address. Legitimately disagrees with `discovery.targets` while a candidate is being refused. |

Both exist only while `riptide.discovery.url` or `riptide.discovery.urls` is set.
`discovery.skipped` is summed across every endpoint in `riptide.discovery.urls`.

### Remote-write sink

Registered only while `riptide.metrics.remote-write.url` is set.
Settings, the series it sends and its validation messages are on the [SNMP metrics reference](snmp-metrics.md).

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`metrics.sink.queueDepth`** | gauge | Samples buffered, waiting to be sent. | approaching `riptide.metrics.remote-write.queue-capacity` |
| **`metrics.sink.droppedSamples`** | counter | Samples offered while the queue was full, or offered while the sink had already begun stopping. | `> 0` |
| **`metrics.sink.failedSamples`** | counter | Samples in a batch a non-2xx, non-retryable status refused, that exhausted `max-attempts` on a retryable one, that were still queued when the shutdown grace period expired, that the flusher had drained but not yet flushed when its thread was interrupted, or that were lost to an unexpected error inside the flusher. | sustained rate |
| **`metrics.sink.sentSamples`** | counter | Samples in a batch the endpoint accepted with a 2xx status. | the base of the delivery arithmetic |
| **`metrics.sink.batchSize`** | histogram | Samples per flushed batch. | not an alert |
| **`metrics.sink.flush`** | timer | Time to encode and POST one batch, retries included. | not an alert |

### SNMP counter collection

Registered whether or not any polling profile sets `collect`, but only marked when one does: a plain interface-name walk with no `collect` set goes through a separate path and moves `snmp.walks`/`snmp.walkDuration` instead.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`snmp.collects`** | meter | Collect attempts against one endpoint's collection definition. | the base of the delivery arithmetic |
| **`snmp.collectDuration`** | timer | Duration of one collect. | not an alert |
| **`snmp.collects.failed`** | meter | Collects that came back with no usable table: a walk failure, timeout, or an `IOException` opening the session. | sustained rate |
| **`snmp.poller.inventoryRegistered`** | gauge | `poll: always` entries currently registered and walked. | a drop below the count the inventory names |
| **`snmp.poller.inventoryRefused`** | gauge | `poll: always` entries that are not polled because of `riptide.snmp.poll.max-exporters`. When the set alone exceeds the cap, this is the whole set. Otherwise it counts the entries that found the cap already filled by flow-registered exporters. `0` when every entry fits. | `> 0` |
| **`snmp.poller.samplesEmitted`** | meter | Samples a walk handed to the metric sink. | compare against `metrics.sink.sentSamples` and `.droppedSamples` |
| **`snmp.poller.collectsFailed`** | meter | Collects the poller itself saw fail: a returned table with no usable rows, or an unexpected exception the collect did not degrade on its own. | sustained rate |

### SNMP poller concurrency

Every poller walk, collecting or not, holds one permit while it is in flight.
An agent whose last walk failed draws from the suspect budget, `riptide.snmp.poll.suspect-pool-width`; every other agent draws from `riptide.snmp.poll.pool-width`.

| Metric | Type | Meaning | Alert on |
| --- | --- | --- | --- |
| **`snmp.poller.inFlight`** | gauge | Walks in flight for agents in good standing. | pinned at `pool-width` together with a rising `deferred` |
| **`snmp.poller.suspectInFlight`** | gauge | Walks in flight for agents whose last walk failed. | not an alert: pinned at `suspect-pool-width` is the bulkhead doing its job |
| **`snmp.poller.deferred`** | meter | Due walks still waiting for a permit at the end of a tick, one mark per waiting walk per second. They start in due order as permits free. | sustained rate while `inFlight` is pinned |
