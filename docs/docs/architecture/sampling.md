---
title: Sampling rates and provenance
sidebar_position: 2
description: How riptide learns a sampling exporter's rate per protocol, why every flow records where its rate came from, how long a learned rate is held, what the option-record tap counters mean, and why the rollups carry the rate as a dimension.
---

# How sampling rates resolve

A sampling exporter states its rate once and then leaves it out of every flow record.
riptide learns the rate from wherever the protocol puts it, records it on every flow beside the counters, and never scales NetFlow or IPFIX `bytes` and `packets` by it.
Stored counters are what the exporter reported; the rate sits alongside them for a query to apply.

## Where a rate comes from, per protocol

| Protocol | Source of the rate | Needs |
| --- | --- | --- |
| NetFlow v9 | The sampler options table, remembered per exporter address and observation domain | The exporter sending the table. On Cisco IOS-XE that is `option sampler-table timeout <seconds>` under `flow exporter`; on IOS-XR `options sampler-table timeout <seconds>` under `flow exporter-map`. |
| IPFIX | Sampler options records (`samplingInterval`, `samplingAlgorithm`), or the RFC 5477 selector parameters of a Selector Report | The exporter advertising either |
| NetFlow v5 | The packet header: a 16-bit field holding a 2-bit mode and a 14-bit interval | Nothing; a v5 exporter has no options table |
| sFlow | The sample itself | Nothing |

For NetFlow v9 the timeout on the sampler table governs how quickly a restarted collector relearns the rate, so a short one is worth setting.
Until the first table arrives, flows from that exporter are recorded as unsampled.

An IPFIX advertisement is recognised by the elements it carries, not by the scope it arrives under.
Exporters disagree about that scope: Juniper inline-jflow uses the observation domain, pmacct the exporting process, softflowd the metering process, and RFC 5476 reserves `selectorId` for a Selector Report.
All are read.
An exporter that states selector parameters (`selectorAlgorithm` with its interval and spacing) rather than an interval outright has its rate computed from them and recorded with provenance `derived`.
Only a record scoped by `selectorId` is kept per Selector; everything else applies to the exporter as a whole.

### One exporter, one rate

A learned rate is keyed by exporter address and observation domain, not by sampler id, because many exporters omit the sampler id from their flow records and there would be nothing to match it against.
An exporter running two samplers at different rates on one observation domain therefore keeps only the most recently advertised.
If you run more than one sampler on one observation domain, the rates need to agree.
A device running several metering processes at different rates keeps the most recent for the same reason.

A receiver accepting both NetFlow v9 and IPFIX from one address treats a v9 source id and an IPFIX observation domain id as the same key.
The two are independent numbering spaces and both often start at 0, so a device exporting both protocols from one address should use distinct ids, or separate receivers.

### NetFlow v5 headers

An interval of zero states no rate, and such a flow is recorded as unsampled unless the receiver has a `flow-sampling-interval-fallback`.
The mode bits do not carry that meaning.
Many sampling exporters leave them at zero and populate the interval anyway, because the mode is not a mandatory field; pmacct's NetFlow v5 exporter never sets it.
riptide therefore reads a non-zero interval as a rate whether or not the mode is set, as nfdump, pmacct, goflow2 and Akvorado do.

**`trust-header-sampling-interval=false`** restores the older reading, where the header interval is ignored.
It governs only the ambiguous case: an interval given with the mode bits set to something other than 1 or 2.
A header stating mode 1 or 2 together with a non-zero interval is unambiguous and always read, so turning this off cannot make riptide ignore an exporter that signalled properly.
A mode with a zero interval names a method and no rate, so it is not affected either way; the fallback supplies the number.

### The fallback is a last resort

For NetFlow v9 and IPFIX, what the exporter says always wins: a rate on the flow record first, then the sampler options table, then the receiver's fallback, then unsampled.
An exporter that explicitly reports an interval of 1 has said it does not sample, and that answer stands over the fallback.
For NetFlow v5 the rung above the fallback is the packet header rather than an options table; the fallback serves a v5 exporter that samples but leaves the header field at zero, which older firmware does.

The fallback applies to the whole receiver, so exporters sharing a port share it.
Give exporters that sample at different rates their own receivers and ports, unless they state their rates themselves, in which case each is read individually and the fallback never comes up.

## Why every flow records its provenance {/* #where-a-rate-came-from */}

A stored `samplingInterval` of `1` is ambiguous on its own.
It can mean the exporter stated that it does not sample, which is an answer and outranks a configured fallback, or that nothing stated a rate anywhere and riptide recorded `1` in the absence of one.
Those are opposite facts, and a query cannot separate them by value.
So every flow carries `samplingProvenance`, the rung that supplied its interval; the values are listed on the [receivers reference](../reference/receivers.md#samplingprovenance-values).

Two values deserve care.

`derived` is not something the exporter said.
It is a rate riptide computed from the selector algorithm and the ranges the exporter supplied, so it carries less authority than `record` and is the value to distrust first when numbers look wrong.

`record` on an sFlow row does not mean the row should be multiplied.
sFlow rates are always on the sample, but sFlow counters are already scaled at ingest, so `flowProtocol` rather than the provenance is what tells a query whether to scale.

Rows written before the column existed read `''`.
They are not backfilled and cannot be: reconstructing them would need each exporter's rate at each past moment, which is precisely the information whose absence this column records.

An exporter alternating between two provenances is worth investigating.
Firmware that populates the sampling field on some export paths and not others produces an interleaved mix of `header` and `assumed`; a sampler options table that expires between refreshes produces the same pattern with `options` and `assumed`.
The query that finds it is on [Query sampling-corrected volume](../guides/sampling-corrected-volume.md#find-exporters-whose-rate-is-not-resolving-consistently).

## How long a learned rate is held

A learned rate is held for 24 hours after the exporter last advertised it.
That is longer than any refresh interval in normal use, so a rate does not expire between refreshes: the slowest platform default is IOS-XR's 1800 s, and IOS-XE's is 600 s.
An IPFIX `option-refresh-rate` is set by the operator rather than defaulted, so a refresh interval beyond a day would still flap, and `parser.optionSampling.expired` is what shows it.

The window is deliberately far longer than it needs to be.
A rate change is pushed: the exporter re-advertises and the new value overwrites.
So the window never guards against a stale wrong rate; it only decides how long a rate outlives an exporter that has gone quiet.
Holding one too long serves a value that was true recently.
Dropping one too early records a known-wrong `1` as though it were an answer, every refresh cycle, indefinitely.
The cost is that a decommissioned exporter's rate lingers for a day before it is dropped.

Drops are counted, and the counter that moved says what the flows fall back to:

- **`parser.optionSampling.expired`**: the exporter has nothing left to resolve against, so its flows fall to the configured fallback, or to `assumed` where none is set.
- **`parser.selectorReport.expired`**: only that Selector's entry is gone. Flows naming it fall back to the exporter-wide rate, so provenance moves from `derived` to `options` and the interval may not change at all.
- **`parser.optionSampling.evicted`** and **`parser.selectorReport.evicted`**: the table is full and displacing entries, including live ones. That is pressure, not silence, and wants a different response.

An explicit withdrawal, an exporter re-advertising an interval of `0` to say it has turned sampling off, drops the entry at once and is counted as neither.
It counts under `parser.options.recognisedUnusable`, described next: the record was understood, and nothing was kept from it.

## Option records nobody used

The per-consumer counters say what each consumer did.
They cannot say what happened to a record no consumer wanted, and that gap once hid a hundredfold sampling undercount until someone read an exporter's source.
Four meters at the tap answer it, against a denominator:

| Meter | Meaning |
| --- | --- |
| **`parser.options.offered`** | Option data records seen. Always equals the sum of the other three. |
| **`parser.options.claimed`** | Stored by at least one consumer. |
| **`parser.options.recognisedUnusable`** | Understood by a consumer, which stored nothing. |
| **`parser.options.unrecognised`** | No consumer knew the shape. |

`unrecognised` is normal and rarely urgent.
VRF tables and metering-process statistics are routine on real exporters and riptide has no consumer for them.
Expect a steady rate and alert on changes rather than on presence.

`recognisedUnusable` is the one to watch.
It means riptide understood a record and served nothing from it: an exporter told it something and it was not kept.
The shapes that reach it today:

- an application table row with a name but no usable `applicationId`, or an id of `0`, or a name longer than 64 characters;
- an interface option record naming no `ifIndex`, benign on exporters that tag one direction only;
- an interval of `0`, exporter-wide or per Selector, a withdrawal that is routine when an exporter turns sampling off;
- a sampling advertisement or Selector Report whose algorithm expresses a ratio riptide cannot store, or names an algorithm and omits its parameters.

The last is the one that costs accuracy: a rate the exporter stated is being dropped.
A rate learned earlier from another record keeps serving until it expires, so the effect is delayed rather than immediate; an exporter that never taught a usable rate reports `assumed` or the configured fallback from the start.

These four meters are collector-wide and name no exporter.
When `recognisedUnusable` climbs, the per-consumer `_skipped` meters say which consumer declined: `parser.optionSampling.skipped`, `parser.selectorReport.skipped`, `enrichment.optionInterfaces.skipped` or `enrichment.optionApplications.skipped` moves with it, never `_consumed`.
To find the exporter, query `samplingProvenance` per exporter for the same period and compare against each candidate's advertised sampling configuration.

The vocabulary differs from the per-consumer counters on purpose.
`enrichment.optionInterfaces.skipped` means that table declined a record, which is routine, since most records are not its own.
Only the tap meters describe what became of the record overall.

## Why the rollups carry the rate as a dimension

The 1-minute rollups carry `samplingInterval` and `flowProtocol` as dimensions, so a sampling-corrected total is the same expression against a rollup as against raw `flows`.
Raw `flows` is kept 30 days by default and the rollups 365, so before the rate was carried, sampling-corrected volume was unanswerable beyond the raw table's retention.

The rate is a dimension, not a pre-scaled measure, on purpose.
A pre-scaled `bytesScaled` column would read `0` for every row aggregated before it existed, so a `SUM` spanning the upgrade would come back quietly too small with nothing marking where.
Carrying the rate has no such failure, because `0` is not a rate anything can produce: rows aggregated before the column was appended read the type default `0`, and `WHERE samplingInterval > 0` excludes exactly them.
riptide does not apply that filter for you.
An implicit one would silently drop the older rows, which is the same class of quiet wrongness as a pre-scaled measure.

The protocol is carried for the same reason.
sFlow counters arrive pre-scaled (`bytes = frame length × sampling rate`), so a query must scale each row by its own protocol's factor: `1` for sFlow, the rate for everything else.
In the rollups `flowProtocol` is a `LowCardinality(String)` rather than the raw table's `Enum8`, because an appended enum column would read back as its smallest-numbered member, `NetflowV5`, for every pre-existing row, indistinguishable from a genuine reading, while a string reserves `''`.

### The two boundary bands

The two columns were added in different releases, the rate first and the protocol after it, so their boundaries do not coincide.
A rollup therefore holds up to three bands:

| Band | `samplingInterval` | `flowProtocol` | Correctable? |
| --- | --- | --- | --- |
| Aggregated before the rate was carried | `0` | `''` | No: `bytes × 0` contributes nothing, so the row must be excluded. |
| Aggregated after the rate, before the protocol | `> 0` | `''` | Only if the deployment received no sFlow in that band. `''` is not `'SFLOW'`, so a protocol-aware expression scales any sFlow inside it by its rate and inflates it. |
| Fully marked | `> 0` | a protocol name | Yes. |

The predicate pair `samplingInterval > 0 AND flowProtocol != ''` selects the fully marked band.
Dropping either predicate is a different kind of wrong: without the first the total is quietly too small, without the second any sFlow in the middle band is inflated.
The how-to shows how to size the bands and when the one-predicate form is still right: [Query sampling-corrected volume](../guides/sampling-corrected-volume.md).

Raw `flows` has no such bands.
`flowProtocol` has been there since the table was created, and a raw row that predates `samplingInterval` reads the column default of `1.0`, not `0`.
Until the release that carried the rate into the rollups, the sFlow parser passed its wire rate through unchecked, so an out-of-spec agent sending `0` produced raw rows carrying `samplingInterval = 0`.
Those rows carry no volume, since the same unchecked rate scaled their counters to `bytes = 0, packets = 0`, so a sum is unaffected, but a `count()` or `sum(flowCount)` with `WHERE samplingInterval > 0` drops them and they are real flow records.
They persist for the raw table's retention after the upgrade, and a backfill with `INSERT INTO … SELECT` carries them into a rollup, where the two meanings of `0` become indistinguishable.

The rate and the protocol are carried for correctness, not offered as something to group by.
Asking riptide's own tools to group by `samplingInterval` or `flowProtocol` answers from raw `flows`, not from a rollup; writing the SQL yourself against a rollup works.

`!= 'SFLOW'` in a query encodes riptide's current behaviour, not a law of the protocol.
sFlow is the only protocol whose counters riptide pre-scales today.
If that ever changes, a saved query testing for `'SFLOW'` by name keeps running and silently returns the wrong number, and riptide cannot warn you inside your own SQL, so check the expression against these docs when you upgrade.

## Related

- [Receivers reference](../reference/receivers.md) for the keys and the provenance values.
- [Query sampling-corrected volume](../guides/sampling-corrected-volume.md) for the SQL.
- [Rollups](rollups.md) for how a rollup gains a dimension in place.
- [Metrics reference](../reference/metrics.md) for the `parser.optionSampling.*`, `parser.selectorReport.*` and `parser.options.*` families.
