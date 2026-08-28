---
sidebar_position: 1
title: Receivers
---

# Receivers

Receivers are the flow listeners. None are configured by default — the daemon starts no
listeners until you define them. Entries defined in the bundled `application.properties`
merge with (and cannot be removed by) external configuration.

Each receiver has a free-form name and three core settings:

| Setting | Values |
|---|---|
| `type` | `netflow5` · `netflow9` · `ipfix` · `sflow` · `multi` |
| `host` | bind address, e.g. `0.0.0.0` |
| `port` | UDP port (IPFIX also listens on TCP; sFlow's conventional port is 6343) |

A `multi` receiver parses all four protocols on one port, telling them apart by their
version words; per-protocol flags (`netflow5`, `netflow9`, `ipfix`, `sflow`, all
defaulting to `true`) switch individual parsers off.

```properties
riptide.receivers.ipfix.type=ipfix
riptide.receivers.ipfix.host=0.0.0.0
riptide.receivers.ipfix.port=4739

riptide.receivers.nf9.type=netflow9
riptide.receivers.nf9.host=0.0.0.0
riptide.receivers.nf9.port=2055
```

Or as YAML (`/etc/riptide/config.yaml`):

```yaml
riptide:
  receivers:
    ipfix:
      type: ipfix
      host: 0.0.0.0
      port: 4739
    nf9:
      type: netflow9
      host: 0.0.0.0
      port: 2055
```

## Timeout fallbacks

NetFlow v9, IPFIX and `multi` receivers accept fallback flow timeouts.
An exporter that reports its own active or inactive timeout always wins.
The fallback fills the gap for exporters whose templates omit the field.

| Setting | Applies when |
|---|---|
| `flow-active-timeout-fallback` | the record carries no active flow timeout |
| `flow-inactive-timeout-fallback` | the record carries no inactive flow timeout |

**Set both or neither.** riptide derives a flow's delta-switched time only when both timeouts resolve, so supplying one alone leaves the result unchanged from supplying nothing.

Both take a duration, written either with a suffix (`5m`, `30s`) or in ISO-8601 (`PT5M`).
A bare number is milliseconds, so write `300s` rather than `300` for five minutes.

```properties
riptide.receivers.nf9.type=netflow9
riptide.receivers.nf9.host=0.0.0.0
riptide.receivers.nf9.port=2055
riptide.receivers.nf9.flow-active-timeout-fallback=5m
riptide.receivers.nf9.flow-inactive-timeout-fallback=30s
```

An IPFIX receiver also takes `transport` (`UDP`, the default, or `TCP`).

## Sampling rate

A sampling exporter states its rate once in a sampler options table and then leaves it out of every flow record.
For **NetFlow v9**, riptide reads that table and remembers the rate per exporter and observation domain, so no configuration is normally needed.

Most platforms only send the table if asked.
On Cisco IOS-XE that is `option sampler-table timeout <seconds>` under `flow exporter`; on IOS-XR it is `options sampler-table timeout <seconds>` under `flow exporter-map`.
The timeout governs how quickly a restarted collector relearns the rate, so a short one is worth setting.
Until the first table arrives, flows from that exporter are recorded as unsampled.

**IPFIX** gets the same correlation, from the sampler options records its exporters advertise. Juniper inline-jflow states `samplingInterval` and `samplingAlgorithm` scoped to the observation domain, and riptide reads both.

An advertisement is recognised by the elements it carries, not by the scope it arrives under.
Exporters disagree about that scope — Juniper uses the observation domain, pmacct the exporting process, softflowd the metering process, and RFC 5476 reserves `selectorId` for a Selector Report — and all are read.
An exporter that states the RFC 5477 selector parameters (`selectorAlgorithm` with its interval and spacing) rather than an interval outright has its rate computed from them, recorded with provenance `derived`.

Only a record scoped by `selectorId` is kept per Selector; everything else applies to the exporter as a whole.
A device running several metering processes at different rates therefore keeps only the most recently advertised, the same caveat as the note below.

:::note[One exporter, one rate]
A rate learned this way is remembered per exporter address and observation domain, so an exporter running two samplers at different rates keeps only the most recently advertised of them. Riptide does not key the rate by sampler id, because many exporters omit that id from their flow records and there would be nothing to match it against. If you run more than one sampler on one observation domain, the rates need to agree.

For the same reason, a receiver accepting **both** NetFlow v9 and IPFIX from one address treats a v9 source id and an IPFIX observation domain id as the same key. The two are independent numbering spaces and both often start at 0, so a device exporting both protocols from one address should use distinct ids, or separate receivers.
:::

**NetFlow v5 has no options-table mechanism**, so there is nothing for riptide to correlate.

A v5 exporter states its rate in the packet header instead, in a single 16-bit field holding a 2-bit sampling mode and a 14-bit interval.
riptide reads both, so a sampling v5 exporter is recorded correctly without configuration.

An interval of zero states no rate, and riptide records such a flow as unsampled unless a fallback is configured below.
The mode bits do not carry that meaning: many exporters that sample leave them at zero and populate the interval anyway, because the mode is not a mandatory field — pmacct's NetFlow v5 exporter never sets it.
riptide therefore reads a non-zero interval as a rate whether or not the mode is set, as nfdump, pmacct, goflow2 and Akvorado do.

If you need the old behaviour, where the header interval was ignored:

```properties
riptide.receivers.nf5.trust-header-sampling-interval=false
```

This setting exists only on `netflow5` and `multi` receivers.
Setting it on a `netflow9`, `ipfix` or `sflow` receiver fails startup, because riptide rejects properties a receiver does not define.

It governs only the ambiguous case: an interval given with the mode bits set to something other than 1 (deterministic) or 2 (random).
A header stating mode 1 or 2 *together with* a non-zero interval is unambiguous and always read, so turning this off cannot make riptide ignore an exporter that signalled properly.
A mode with a zero interval names a method and no rate, so it is not affected either way — the fallback below supplies the number.

For an exporter that never advertises a rate, name it yourself:

```properties
riptide.receivers.nf9.flow-sampling-interval-fallback=1000
```

For NetFlow v9 and IPFIX this is a last resort, not an override.
What the exporter says always wins: a rate on the flow record first, then the rate from its sampler options table, then this setting, then unsampled.
An exporter that explicitly reports an interval of 1 has said it does not sample, and that answer stands over the fallback.

The same holds for **NetFlow v5**, where the rung above the fallback is the packet header rather than an options table.
It applies to a dedicated `netflow5` receiver and to the v5 half of a `multi` receiver alike:

```properties
riptide.receivers.nf5.type=netflow5
riptide.receivers.nf5.flow-sampling-interval-fallback=1000
```

Use it for a v5 exporter that samples but leaves the header field at zero, which older firmware does.

Note the fallback applies to the whole receiver, so exporters sharing a port share it.
Give exporters that sample at different rates their own receivers and ports, unless they state their rates in the header — in which case each is read individually and the fallback never comes up.

### Where a rate came from

A stored `samplingInterval` of `1` is ambiguous on its own.
It can mean the exporter stated that it does not sample, which is an answer and outranks a configured fallback, or that nothing stated a rate anywhere and riptide recorded `1` in the absence of one.
Those are opposite facts, and a query cannot separate them by value.

Every flow therefore carries `samplingProvenance` — the rung of the ladder that supplied its interval:

| value | the rate came from |
|---|---|
| `record` | the flow record itself (NetFlow v9 / IPFIX fields 34, 49, 50), or an sFlow sample |
| `options` | the exporter's sampler options table (NetFlow v9 or IPFIX) |
| `header` | the NetFlow v5 packet header |
| `derived` | riptide's own arithmetic on the parameters of an IPFIX Selector Report |
| `fallback` | the receiver's `flow-sampling-interval-fallback` |
| `assumed` | nothing stated a rate; `1` was recorded in the absence of one |
| `''` (empty) | the row was written before this column existed |

Two of these deserve care.

`derived` is not something the exporter said.
It is a rate riptide computed from the selector algorithm and the ranges the exporter supplied, so it carries less authority than `record` and is the value to distrust first when numbers look wrong.

`record` on an **sFlow** row does not mean the row should be multiplied.
sFlow rates are always on the sample, but sFlow counters are already scaled at ingest, so the caveat below still applies and `flowProtocol` — not the provenance — is what tells you.

Rows written before this column existed read `''`.
They are not backfilled, and cannot be: reconstructing them would need each exporter's rate at each past moment, which is precisely the information whose absence this column records.

An exporter alternating between two provenances is worth investigating.
Firmware that populates the sampling field on some export paths and not others produces an interleaved mix of `header` and `assumed` for one exporter; a sampler options table that expires between refreshes produces the same pattern with `options` and `assumed`:

```sql
SELECT exporterAddr, samplingProvenance, samplingInterval, count() AS flows
FROM flows
WHERE timestamp > now() - INTERVAL 1 HOUR
GROUP BY exporterAddr, samplingProvenance, samplingInterval
ORDER BY exporterAddr, flows DESC
```

More than one provenance for one exporter means its rate is not resolving consistently.

A learned rate is held for 24 hours after the exporter last advertised it.
That is longer than any refresh interval in normal use, so a rate does not expire between refreshes — the slowest platform default is IOS-XR's 1800 s, and IOS-XE's is 600 s.

An IPFIX `option-refresh-rate` is set by the operator rather than defaulted, so a refresh interval beyond a day would still flap. `parser_optionSampling_expired` below is what shows it.

The window is deliberately far longer than it needs to be. A rate change is pushed — the exporter re-advertises and the new value overwrites — so this window never guards against a stale *wrong* rate; it only decides how long a rate outlives an exporter that has gone quiet. Holding one too long serves a value that was true recently. Dropping one too early records a known-wrong `1` as though it were an answer, every refresh cycle, indefinitely.

The cost is that a decommissioned exporter's rate lingers for a day before it is dropped.

Drops are counted:

```
parser_optionSampling_expired    rates dropped after the exporter stopped advertising
parser_selectorReport_expired    the same, for IPFIX Selector Reports
parser_optionSampling_evicted    entries displaced by table pressure rather than by silence
parser_selectorReport_evicted
```

A climbing `_expired` means an exporter is losing its learned rate. What its flows fall back to depends on which counter moved:

- `parser_optionSampling_expired` — the exporter has nothing left to resolve against, so its flows fall to the configured fallback, or to `assumed` where none is set.
- `parser_selectorReport_expired` — only that Selector's entry is gone. Flows naming it fall back to the exporter-wide rate, so provenance moves from `derived` to `options` and the interval may not change at all.

The `_evicted` counters mean something different: the table is full and displacing entries, including live ones. That is pressure, not silence, and it wants a different response.

An explicit withdrawal — an exporter re-advertising an interval of `0`, meaning it has turned sampling off — drops the entry at once and is counted as neither.

The query above finds these conditions after the fact; the counters find them as they happen.

### Option records nobody used

Those counters say what each consumer *did*. They cannot say what happened to a record no consumer wanted, and that is the gap that hid a hundredfold sampling undercount until someone read an exporter's source. Three counters at the tap answer it, against a denominator:

```
parser_options_offered              option data records seen
parser_options_claimed              stored by at least one consumer
parser_options_recognisedUnusable   understood by a consumer, which stored nothing
parser_options_unrecognised         no consumer knew the shape
```

`offered` always equals the sum of the other three.

**`unrecognised` is normal and rarely urgent.** VRF tables, application tables and metering-process statistics are routine on real exporters and riptide has no consumer for them. Expect a steady rate here and alert on changes rather than on presence.

**`recognisedUnusable` is the one to watch.** It means riptide understood a record and served nothing from it — an exporter told it something and it was not kept. Two shapes reach it today: an interface option record naming no `ifIndex`, and a sampling advertisement whose algorithm expresses a ratio riptide cannot store exporter-wide. The first is benign on exporters that tag one direction only; the second means an exporter's stated rate is being dropped, and flows from it will report `assumed` or the configured fallback instead.

If it climbs on an exporter you care about, compare `parser_optionSampling_consumed` and `enrichment_optionInterfaces_consumed` for the same period to see which consumer looked, then check that exporter's advertised sampling configuration against what `samplingProvenance` reports for its flows.

Note the vocabulary differs from the per-consumer counters on purpose: `enrichment_optionInterfaces_skipped` means *that* table declined a record, which is routine — most records are not its own. Only these three describe what became of the record overall.

:::warning[NetFlow v5 rates changed]
Earlier releases ignored the v5 header interval. What they recorded instead depended on the receiver:

- **No fallback configured** — every v5 flow was recorded as unsampled, `samplingInterval = 1`. Flows from an exporter that advertises a rate now carry that rate, so a query multiplying by `samplingInterval` returns a **larger** and more correct answer.
- **`flow-sampling-interval-fallback` configured** — every v5 flow carried the configured value, whatever the exporter said. The exporter's own rate now wins, so the recorded value can move in **either** direction. An exporter advertising 1:20 behind a receiver configured for 1000 drops from 1000 to 20, and a query multiplying by the rate returns an answer 50× smaller than before — and correct, where the old one was not.

Stored `bytes` and `packets` are unchanged either way, and no query errors.

Rows written before the upgrade are not rewritten: correcting them would need each exporter's rate at each past moment, which is exactly what was never recorded. A query spanning the upgrade mixes both conventions.

The rows whose meaning changed name themselves. Find which exporters now report a rate, and when each changed:

```sql
SELECT exporterAddr, samplingInterval, min(timestamp), max(timestamp)
FROM flows
WHERE flowProtocol = 'NetflowV5' AND samplingProvenance = 'header'
GROUP BY exporterAddr, samplingInterval
ORDER BY exporterAddr, min(timestamp)
```

The `min(timestamp)` of the earliest row per exporter is when that exporter's rate started being read.
Rows from before the upgrade carry a different provenance (`fallback`, `assumed`, or `''` if they predate the column), so no timestamp guess is needed to tell the two conventions apart.

**If you compensated for this by hardcoding a multiplier** in a dashboard or query — because you knew riptide was not reading these exporters' rates — remove it, or you will now double-count.

To pin the old behaviour while you correct queries, set `trust-header-sampling-interval=false` on the affected **`netflow5` and `multi`** receivers — it is not accepted on other receiver types and will fail startup there.
Note this only suppresses the header for exporters that leave the mode bits unset; an exporter stating mode 1 or 2 with a rate is always read, so for those the old value cannot be restored by configuration.
Set `flow-sampling-interval-fallback` to the value you were relying on if you need a specific rate recorded.
:::

:::note
riptide records the sampling rate; it does not scale NetFlow or IPFIX `bytes` and `packets` by it.
Stored counters are what the exporter reported, and the rate sits alongside them for a query to apply.

Four things to know before writing that query.
sFlow already scales at ingest (`bytes = frame_length × sampling_rate`) and still reports its rate, so multiplying sFlow rows again double-counts them: scale each row by its own protocol's factor rather than by its rate alone.
The 1-minute rollups carry the rate and the protocol, so the corrected expression is the same query against either table. See below.
A rate of `1` is not always a statement that the exporter does not sample — check `samplingProvenance` before treating one as trustworthy, and see [Where a rate came from](#where-a-rate-came-from).
And if you are coming from **nfdump or pmacct**, check whether counters were being scaled for you.
Both can multiply NetFlow v5 `bytes` and `packets` by the sampling rate at ingest (in pmacct via `nfacctd_renormalize`, in nfdump depending on how sampling was detected or forced with `-s`), where riptide never does.
Counters stored here are always as the exporter reported them, so a query ported from either may need the multiplication added rather than removed.
:::

### Sampling-corrected volume beyond raw retention

The 1-minute rollups carry `samplingInterval` and `flowProtocol` as dimensions, so the **scaling expression** is the same against either table. The `WHERE` clause is not, and that difference is not cosmetic — see the warning below.

:::note[Requires the rollups to carry the rate and the protocol]
A deployment whose collector runs in validate mode (`manage-schema: false`, which is the multi-tenant default) gains them only when an admin re-runs `riptide onboard` — until then the query below fails with `UNKNOWN_IDENTIFIER`, and riptide answers long-range queries from raw `flows` instead of the rollups. See [ClickHouse: upgrading an existing deployment](./clickhouse.md#rollups).
:::

Against a **rollup**:

```sql
SELECT sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval)) AS corrected_bytes
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY
  AND samplingInterval > 0     -- excludes rows aggregated before the rate was carried
  AND flowProtocol != '';      -- excludes rows aggregated before the protocol was carried
```

Against raw **`flows`**, the same scaling expression with **no boundary predicates at all**:

```sql
SELECT sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval)) AS corrected_bytes
FROM riptide.flows
WHERE timestamp >= now() - INTERVAL 7 DAY;
```

:::danger[Do not copy the rollup's `WHERE` clause onto raw `flows`]
`flowProtocol != ''` **fails outright** against raw `flows`. There the column is an `Enum8` whose every member is a real protocol, so `''` is not a value it can be compared against:

```
Code: 691. DB::Exception: Unknown element '' for enum:
while converting '' to Enum8('NetflowV5' = 1, 'NetflowV9' = 2, 'IPFIX' = 3, 'SFLOW' = 4)
```

The boundary predicates exist because a rollup dimension is *appended* to rows that already exist, and those rows read a reserved value. Raw `flows` has no such band: `flowProtocol` has been there since the table was created, and a raw row that predates `samplingInterval` reads the column default of `1.0`, not `0`. Both predicates are therefore not just unnecessary on raw `flows` — one of them is an error.
:::

Each row is scaled by its own protocol's factor: sFlow counters arrive pre-scaled, so their factor is `1`, and everything else is scaled by its rate.

That matters because raw `flows` is kept 30 days by default and the rollups 365. Before the rate was carried, sampling-corrected volume was simply unanswerable beyond the raw table's retention.

:::warning[Two boundary predicates, not one]
The two clauses are not redundant, and dropping either one is a different kind of wrong.

Without `samplingInterval > 0`, rows aggregated before the rate was carried contribute `bytes × 0` and the total comes back quietly too small. A 90-day range necessarily spans that upgrade.

Without `flowProtocol != ''`, rows aggregated after the rate was carried but before the protocol was read `''` — and `''` is not `'SFLOW'`, so the `if()` scales them by their rate. Any sFlow inside that band is inflated, which is exactly the defect the protocol column was added to fix.

There are two predicates because the two columns were added at different times, the rate first and the protocol after it. Rows aggregated in between know their rate and not their protocol. Check how much of your data is in that band with:

```sql
SELECT countIf(samplingInterval = 0)                            AS before_rate,
       countIf(samplingInterval > 0 AND flowProtocol =  '')     AS rate_only,
       countIf(samplingInterval > 0 AND flowProtocol != '')     AS both,
       count()                                                  AS total
FROM riptide.flows_by_conversation_1m
WHERE timestamp >= now() - INTERVAL 90 DAY;
```

`both` uses the same pair of predicates as the corrected query, so it is exactly the row count that query reads. The three counters sum to `total` on a rollup fed only by riptide. If they do not, you have rows carrying `samplingInterval = 0` **and** a known protocol — which a materialized view cannot produce, but the `INSERT INTO … SELECT` backfill described further down can. Bound the window: a rollup holds 365 days.
:::

:::caution[Single protocol? Keep the one-predicate form]
If your deployment receives no sFlow, nothing in the rate-only band was ever wrong — every row there is correctable by `sum(bytes * samplingInterval) WHERE samplingInterval > 0`, which is what this page recommended before the protocol was carried. Adding `flowProtocol != ''` would discard that band for up to 365 days to guard against traffic you do not receive.

Confirm with `SELECT count() FROM riptide.flows WHERE flowProtocol = 'SFLOW'` inside the raw retention window, and keep the one-predicate form if it is zero and your exporters have not changed.
:::

:::tip[Two different questions]
`sum(bytes * if(flowProtocol = 'SFLOW', 1, samplingInterval))` is total corrected volume: every byte in the fully-marked band, each scaled by its own protocol's factor. "Fully-marked" is doing real work there — the two boundary predicates exclude everything aggregated before each column was carried, so on a deployment that upgraded today a 90-day window is mostly excluded. Size the bands with the `countIf` query above before reading the total as a fleet figure.

`sum(bytes * samplingInterval) … WHERE samplingInterval > 0 AND flowProtocol NOT IN ('', 'SFLOW')` is something narrower — corrected volume of the NetFlow/IPFIX subset. It is short by every sFlow byte you receive, because it drops those rows rather than scaling them by `1`. That is a legitimate query when you want the subset. It is not "the corrected total", and it is worth being explicit about that, because it is easy to reach for the exclusion on a mixed deployment and read the answer as a total.

Note the `''` in that exclusion list. Writing the subset query as bare `WHERE flowProtocol != 'SFLOW'` looks right and is not: middle-band rows carry `''`, which is not `'SFLOW'`, so they pass the filter and get multiplied by their rate — reintroducing the exact inflation this column exists to remove, in the query meant to avoid it.
:::

:::caution[`!= 'SFLOW'` encodes riptide's behaviour, not a law of the protocol]
sFlow is currently the only protocol whose counters riptide pre-scales. If that ever changes, a saved query or dashboard testing for `'SFLOW'` by name keeps running and silently returns the wrong number. Riptide cannot warn you inside your own SQL, so check this expression against these docs when you upgrade.
:::

:::caution[The corrected total is a `Float64` and drifts past 2^53]
`samplingInterval` is a `Float64`, so `bytes * …` is evaluated in floating point and the sum loses precision above 2^53 (~9×10^15). At `bytes = 12345678901234567` and a rate of `1000`, ClickHouse 26.7 returns `12345678901234567168` where the exact product ends `...000` — a drift of 168.

Irrelevant at any realistic volume: 2^53 bytes is about 9 petabytes in a single group-minute. If you do query ranges that large and need exactness, cast both factors first:

```sql
sum(toUInt128(bytes) * toUInt128(if(flowProtocol = 'SFLOW', 1, samplingInterval)))
```

That is not the recommended form because it costs more and a fractional rate would truncate; use it only when you have a reason.
:::

The rate is a **dimension**, not a pre-scaled measure. A pre-scaled `bytesScaled` column would read `0` for every row aggregated before it existed, so a `SUM` spanning the upgrade would come back quietly too small with nothing marking where. Carrying the rate has no such failure, because `0` is not a rate anything can produce.

:::warning[Rows aggregated before the rate was carried read `samplingInterval = 0`]
A rollup gains a dimension in place, and rows already aggregated cannot be revisited — a materialized view does not backfill. Those rows read the column's type default, which is `0`.

`0` is never a real rate: riptide admits only finite values `>= 1.0`, and records `1.0` when nothing states one. So the boundary is a predicate rather than a date you have to remember:

```sql
-- only rows aggregated since the rate was carried
WHERE samplingInterval > 0
```

Riptide does **not** apply that filter for you. An implicit one would silently drop the older rows, which is the same class of quiet wrongness as a pre-scaled measure. Add it when you want the corrected total, leave it off when you want everything.
:::

:::caution[In raw `flows`, `0` has one other source]
Until the release that carried the rate into the rollups, the sFlow parser passed its wire rate through unchecked, so an out-of-spec agent sending `0` produced raw rows carrying `samplingInterval = 0`. Those rows persist for the raw table's retention (30 days by default) after the upgrade.

They carry no volume — the same unchecked rate scaled their counters, so they were stored as `bytes = 0, packets = 0`. `sum(bytes * samplingInterval)` is therefore unaffected either way, but a `count()` or `sum(flowCount)` over raw `flows` with `WHERE samplingInterval > 0` drops them, and they are real flow records. Check with `SELECT count() FROM riptide.flows WHERE samplingInterval = 0`.

In the rollups, `0` means only what the warning above says. Backfilling a rollup from raw with `INSERT INTO … SELECT` carries those rows across, where the two meanings become indistinguishable.
:::

The rate and the protocol are carried for correctness, not offered as something to group by — asking riptide's tools to group by `samplingInterval` or `flowProtocol` answers from raw `flows`, not from a rollup. Writing the SQL yourself against a rollup works and is not discouraged; it is riptide's own query routing that does not yet pick a rollup for those.

## Exporter identity

Flows are attributed to their exporter by **source address plus observation domain**
(IPFIX, RFC 7011) or **source ID** (NetFlow v9) — two observation domains behind one
exporter IP are distinct identities. NetFlow v5 has no such concept; its engine type/ID
are mapped onto the domain. The identity drives node matching — see
[SNMP agents](agent-configuration.md).

**sFlow identity lives in the payload**: the datagram's `agent_address` plus
`sub_agent_id` — *not* the UDP source address, which may be a different management IP
entirely (or a shared socket in front of many agents). Node matching runs against the
agent address, and the node `observation-domain` key pins sub-agent IDs.

## sFlow semantics

sFlow v5 is packet sampling, not a flow cache. Each flow sample becomes one flow whose
volume is the statistical estimate (`bytes = frame length × sampling rate`,
`packets = sampling rate`) and whose first/last-switched collapse to the receive time.
Sampled headers are decoded down to addresses, ports, and TCP flags; whatever a
truncated or non-IP header doesn't reveal stays at its floor value and the flow is
still persisted (see [Enrichment](../enrichment.md)). Counter samples are skipped —
riptide does not interpret them.

## Session state bounds

Riptide keeps per-exporter state on the UDP ingest path: NetFlow v9 and IPFIX templates, sequence
trackers, and the interface names exporters push as option records. All of it is keyed on the
exporter **scope identity** — `(source address, observation domain)` for v9 and IPFIX,
`(agent address, sub-agent ID)` for sFlow.

Every part of that identity is chosen by the sender. The observation domain is a 32-bit header
field, and both halves of the sFlow pair come out of the datagram payload rather than the UDP
header. A sender that varies one of them mints a new identity on every packet, so the state is
bounded rather than left to grow.

```yaml
riptide:
  flows:
    session:
      max-sources: 4096            # distinct UDP sources retaining state
      max-scopes-per-source: 16    # scope identities per source
      max-ifindexes-per-scope: 1024 # interface entries per scope
      source-idle-timeout: 30m     # release a source's slot after this much silence
```

All four must be positive. Zero or negative is refused at startup, naming the property, because a
zero would disable the bound rather than tighten it — and `max-sources: 0` would refuse every
exporter and stop v9/IPFIX decoding altogether.

### Sizing

Worst-case retained state is a product you can multiply out:

```
session tables : max-sources × max-scopes-per-source × ~852 B
option table   : max-sources × max-scopes-per-source × max-ifindexes-per-scope × ~144 B
```

Read the second line as a ceiling, not an expectation. Reaching it means an attacker holding all
`max-sources` slots at once. What a single source can spend is
`max-scopes-per-source × max-ifindexes-per-scope × ~144 B` — about 2.4 MB at the defaults. A real
fleet holds one scope per exporter and its own interfaces, far below either number.

The defaults suit real hardware: a per-linecard chassis exporting several observation domains from
one address, and a large router carrying up to a thousand interfaces once subinterfaces are counted.

### When a bound is reached

Behaviour differs by level, on purpose.

| Bound | On reaching it | Effect |
|---|---|---|
| `max-sources` | New sources refused; admitted ones keep their state | New exporters are not retained until a slot frees |
| `max-scopes-per-source` | That source's least-recently-used scope is displaced | Confined to that source — no other exporter is affected |
| `max-ifindexes-per-scope` | That scope's least-recently-used interface is evicted | Degrades only: static pins and live SNMP still resolve the interface, and the flow is still emitted |

Only the source bound refuses; the other two evict least-recently-used within their own level. That
asymmetry is deliberate. Evicting across sources would let whoever sends hardest choose which of
your devices stop being monitored, so eviction is always confined to the source that caused it.

### Watching them

```
flows.session.sources               # gauge: sources currently holding a slot
flows.session.scopes                # gauge: admitted scope identities
flows.session.rejectedSources       # meter: source bound reached
flows.session.rejectedScopes        # meter: a scope was displaced
enrichment.optionInterfaces.rejected # meter: an interface entry was evicted
```

A rejection meter climbing steadily on a healthy fleet means the bound is too low for your
hardware, not that you are under attack — raise the matching setting. Rejections are also logged at
warning level, rate-limited, with separate limiters per bound so a noisy scope flood cannot mask the
more serious source-bound message.

Keep `source-idle-timeout` at or above the receiver's template timeout. They are separate timers:
the slot and the state it authorises would otherwise expire at different moments, and state
retained after its slot is released puts the real total above the configured ceiling. Riptide warns
at startup if the template timeout is the longer of the two.

### What this is not

Bounding is not authentication. Riptide does not verify that an exporter is who it claims to be,
and these limits do not make it safe to expose a flow port to untrusted networks. They cap the cost
of an abusive sender; they do not identify one.

Restrict the flow port to known exporters. Note that a source ACL constrains the *source* bound but
does not blunt the payload-borne multiplier: a single permitted sender can still vary observation
domain or sFlow agent address freely, which is exactly what `max-scopes-per-source` exists to cap.
