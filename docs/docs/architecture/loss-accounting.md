---
title: Where flows can be lost
sidebar_position: 4
description: The two bounded queues and the socket in front of them, what each loss counter counts exactly, why droppedRows and failedRows are different statements, the delivery arithmetic, and what the parser gauges measure.
---

# Where flows can be lost

Flows can be dropped at two bounded queues, and each one counts what it discards.
Nothing is lost silently, but two of the counters count loss that happens before any queue, and one of them is an upper bound rather than a count.
The series themselves are in the [metrics reference](../reference/metrics.md#ingest-loss); this page is what they mean.

## The loss points

| Where | Counter | What is discarded |
| --- | --- | --- |
| Kernel receive buffer | `listeners.<name>.socketDrops` | datagrams, before riptide runs |
| Template cache | `parsers.<name>.undecodableSets` | Data Sets whose template had not arrived |
| Dispatch queue | `parsers.<name>.dispatchDrops` | records, when enrichment or persistence fell behind, or at shutdown |
| Enrichment and persistence | `pipeline.dispatchErrors` | records, when enrichment or persistence threw |
| Persister queue | `persister.batch.droppedRows` | rows the queue never handed to an insert |
| The insert | `persister.batch.failedRows` | rows an insert was attempted for, and lost |

`socketDrops` is upstream of every application counter.
Once the receive buffer overflows, the datagram is gone before riptide runs, so this is the only place that loss is visible at all.
It is read per socket from `/proc/net/udp`, matched on the bound address and port, so it attributes to this receiver rather than to the whole host or to another socket sharing the port number.
It publishes no value on non-Linux platforms; absent is not the same as zero.
A rising value means the collector cannot drain the socket fast enough: raise `net.core.rmem_max`, or reduce offered load.

`undecodableSets` counts Data Sets thrown away because their Template had not arrived.
RFC 7011 §8 permits discarding these, so it is not a protocol error, but it is still lost data.
It counts Sets, not records: without the Template the record size is unknown, so treat it as a lower bound.
It also counts Options Data Sets, whose loss costs enrichment metadata (exporter-pushed interface names) rather than flow records, so a non-zero value is not proof that flow data was lost.
Expect a burst at startup: a UDP exporter re-announces Templates only periodically, so a freshly started collector discards data until the first Template of each exporter arrives.
Sustained non-zero values are the ones to alert on.

Both were added because a lab measurement found the application accounting for only ~4 % of a ~25 % shortfall under sustained overload, with nothing accounting for the rest.

## Datagram and reliable transports differ

A UDP receiver drops when its dispatch queue stays full, because the medium is already lossy and a counted userspace drop beats pushing back into the kernel receive buffer, where the loss is invisible.
An IPFIX over TCP receiver never drops here: the exporter's bytes are already acknowledged and there is no retransmission, so the listener blocks instead, which closes the TCP receive window and makes the exporter slow down.

The persister queue follows the same reasoning.
When it is full, ClickHouse cannot keep up, and the collector drops flows instead of blocking, because blocking would backpressure the parsers into the network socket.
Drops are counted and logged with a rate limit.

## `droppedRows` and `failedRows` are different statements

The two `persister.batch.*` loss counters split on whether an insert was ever attempted.

| | `droppedRows` | `failedRows` |
| --- | --- | --- |
| when | no insert was attempted for the row | the flusher had the row and did not deliver it |
| causes | queue full, repository stopping, producer interrupted, offered after the shutdown drain | insert refused, unexpected `Error` in the flusher, flusher interrupted mid-drain, shutdown grace expired |
| exact? | yes, nothing was sent | no, in two of four cases |
| what it means | ClickHouse cannot keep up, or riptide is shutting down | ClickHouse rejected the write, or riptide died holding it |

`failedRows` covers four cases and is an upper bound on the loss in two of them.
A refused insert may still have committed a prefix of the batch, yet the whole batch is charged; see [Insert batching and dead letters](persistence.md).
The same is true when an unexpected `Error` escapes the flusher, since it may escape with an insert already in flight.
The other two are certain loss: rows the flusher still held when it was interrupted, and rows left over once the shutdown grace period expires.
Neither ever reached the server.

A refused batch increments `failedRows`, not `droppedRows`; the drop counter is for queue-full and post-shutdown loss and stays at zero for that failure.
Since dead-lettering, a refused batch's rows are also kept in `flows_dead_letter` and counted under `deadLetteredRows`, and that is a second statement rather than a correction: those rows are still not in `flows`.
The other three `failedRows` cases are not dead-lettered, because none of them is a batch a reachable server refused.
How to read and replay them is in [Inspect and replay dead letters](../operations/dead-letters.md).

`failedRows` is alertable on a sustained rate, as a signal and not as a loss figure.
A non-zero rate means ClickHouse is rejecting writes riptide had already accepted, which is worth paging on however many rows it turns out to be.
Do not put the number in the alert text as flows lost: it is an upper bound, and in the refused-insert case some of those rows are in the table.
Quote `deadLetterFailedRows` if the alert needs a number that is closer to "gone".
It is deliberately outside the readiness contract, like the rest of the ClickHouse path, so it will not fail `/readyz`; these metrics are the whole story.

## Delivery accounting

`recordsReceived − dispatchDrops − dispatchErrors` is what reached the persister.

Start from `recordsReceived`, not `recordsScheduled`.
`dispatchDrops` counts two populations and only one of them is in `recordsScheduled`: a packet refused by a full queue is charged to `dispatchDrops` and returns before the scheduled mark, while records abandoned at shutdown were scheduled first.
Subtracting all of `dispatchDrops` from `recordsScheduled` therefore removes the queue-full records twice and understates delivery, by exactly the amount that matters, since the queue-full term is the one that grows under overload.
Measured on the saturation case in `ParserDispatchTest`: received 9, scheduled 6, dropped 3, delivered 6; `received − drops` gives 6, `scheduled − drops` gives 3.

`recordsDispatched` does not exclude `dispatchErrors`: the dispatcher catches the failure and returns normally, so the records are marked dispatched and counted as errors both.
Do not read that meter as delivery confirmation.
It disagrees downwards too, in two places: an `Error` escaping the dispatcher skips the mark, and records abandoned at shutdown were scheduled and never dispatched.
`DaemonDispatcherTest` and `ParserDispatchTest` pin both directions.

The arithmetic stops at the persister.
Do not extend it to persisted rows by subtracting `failedRows`, because a refused insert counted in full there may have committed part of its batch.
Query the table for what landed.
Nor does adding `deadLetteredRows` back repair it: a dead-lettered row is in `flows_dead_letter`, not in `flows`, and the prefix the server may have committed is counted in both.

## Memory budget for the queues

Both queues are bounded, so the worst case is the sum, and the dispatch queue costs more than its flow objects.

| Queue | Bound | Cost |
| --- | --- | --- |
| `parsers.<name>` dispatch queue | 4096 packets by default | each queued packet also pins its received datagram buffer until the packet is enriched, about 33 MB of direct memory per receiver at the default 8096-byte buffer size, on top of the heap cost of the flow objects |
| `persister.batch` queue | 40,000 rows by default (`riptide.clickhouse.batch.queue-capacity`) | heap |

A `multi` receiver runs one parser per sub-protocol, each with its own queue and threads, so budget per sub-protocol and size down accordingly if you configure several.

## Elements riptide parses and discards

`parsers.<name>.unmodelledElementTemplates` counts IPFIX templates carrying an information element riptide understands well enough to parse, and then deliberately does not use.

A non-zero reading is not a fault.
Nothing is dropped, no flow is lost, and the export is valid.
It means an exporter is stating something riptide is not reading, and somebody should decide whether that matters.

The log line is per element, per exporter, per parser.
Each watchlisted element is named once for each exporter that announces it, by each parser that sees it.
An exporter announcing IE 396 must not silence the IE 390 arrival this exists to catch, and a lab box announcing IE 390 last year must not silence a production box announcing it today.
The line names the exporter address and observation domain, because the point is that somebody goes and looks at it.

Alert on the total, not on a rate.
This is a monotonic counter for the life of the process, like `undecodableSets`.
A rate computed from it is meaningful over UDP, where an exporter re-announces its templates on a timer, and misleading over TCP, where templates are announced once per connection: the count stops moving while that exporter carries on exporting flow-selection data for the life of the session.
A flat rate on TCP does not mean the condition cleared.

Today the watchlist holds one family: the IPFIX flow-selection elements, IE 390 to 399.
Riptide models packet selection and not flow selection, so an exporter running an Intermediate Flow Selection Process reports its flows at whatever rate its packet selection states, or at 1, with no signal that most of its flows were discarded before export ([issue 596](https://github.com/Riptide-Labs/riptide/issues/596)).

A zero does not mean no such exporter exists.
It means none has sent a template to this collector since it started.
That distinction is why the counter exists: a survey of exporter source concluded the packet-selection family was unimplemented in practice, and softflowd was then found emitting it, with a 1:100 sampled exporter recorded as unsampled and its volume under-reported hundredfold ([issue 598](https://github.com/Riptide-Labs/riptide/issues/598)).

This counter is IPFIX-only.
NetFlow v9 field types are a different numbering space, and a v9 type 390 is not IE 390.

## Parser gauges {/* #parser-gauges-exporters-and-templates */}

Two gauges describe what a UDP parser is holding, and until 0.7.0 `sessionCount` reported the wrong one of the two.

| Metric | Meaning |
| --- | --- |
| `parsers.<name>.sessionCount` | exporters: one per `(session, observation domain)` pair |
| `parsers.<name>.templateCount` | templates held across all exporters |

These two and `dispatchQueueDepth` are registered while the parser runs and deregistered when it stops, so a stopped receiver publishes no series at all rather than a final or zero reading.
Alert on absence, not on a value: a rule like `parsers_<name>_sessionCount == 0` goes stale instead of firing.
A stopped parser used to report its last counts forever while a stopped dispatch queue read `0`, which is indistinguishable from healthy.

`sessionCount` is not a count of exporting processes.
A session is keyed by remote address plus the local socket, and each observation domain within a session counts separately, so:

- one process announcing two observation domains counts 2
- one process sending to two receiver ports counts 2
- two processes behind one NAT address, sharing an observation domain, count 1

What moves `sessionCount` is a new exporter appearing, or an exporter's last template expiring and housekeeping reaping it.
A steady-state re-announcement of a template the exporter has already sent moves neither gauge: `addTemplate` replaces the entry under the same template id.

Only IPFIX and NetFlow v9 populate these gauges.
NetFlow v5 and sFlow carry no templates, so both gauges stay 0 for those receivers no matter how many exporters are sending; a 0 there is not an ingest fault.
On a `multi` receiver each sub-protocol registers its own pair under its own name (`<name>:netflow5`, `<name>:sflow`, and so on), so those pairs read 0 while the IPFIX and NetFlow v9 pairs report real values.

`sessionCount` is only eventually consistent with "holds at least one template": housekeeping expires templates in one pass and reaps the emptied exporters in a second, so the gauge can transiently include an exporter holding none.
An alert on it has to tolerate that flap.

Template cardinality is the more useful of the two for capacity work: it is what drives the per-record cost of the parse path.

## NetFlow v5 sampling rate resolution

NetFlow v5 has no options table, so a v5 flow's sampling rate resolves from the packet header, then the receiver's `flow-sampling-interval-fallback`, then an assumed `1`.
Which rung answered is metered per packet, because the rate lives in the header and every record in a packet resolves identically, and per receiver: `parsers.<name>.samplingRate.header`, `.fallback` and `.assumed`.

`assumed` is not the same statement as an exporter reporting a rate of `1`.
An exporter that states `1` has said it does not sample, and that lands under `header`.
`assumed` means nothing stated a rate at all, and `1` is what riptide wrote in the absence of one.

Each meter's leaf name is the value written to that flow's `samplingProvenance` column, so a meter and the rows it counted always agree.
The meters answer "is this happening now" without a query; the column answers it for any period, for every protocol, and per exporter; the query is in [Sampling rates and provenance](sampling.md).

These exist because the resolution is invisible in the data path: riptide records the rate without applying it, so an exporter that starts or stops advertising changes no counter and raises no error.
A `header` rate that falls to zero means a fleet stopped advertising and is now being recorded at an assumed `1`, or on the configured rate, which may not match.

## Related

- [Exporter identity and session state](session-state.md): the bounds on per-exporter state and what happens when one is reached.
- [Insert batching and dead letters](persistence.md): the batched path these counters describe.
- [Troubleshooting](../operations/troubleshooting.md): what to do when each counter moves.
