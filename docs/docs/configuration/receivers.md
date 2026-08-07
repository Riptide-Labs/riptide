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

IPFIX does not yet get this correlation: its rate is read only from the flow record itself, so an IPFIX exporter that advertises out of band needs the fallback below.

**NetFlow v5 has no options-table mechanism**, so there is nothing for riptide to correlate.
A v5 exporter states its rate in the packet header instead, in a single 16-bit field holding a 2-bit sampling mode and a 14-bit interval.
riptide reads both, so a sampling v5 exporter is recorded correctly without configuration.

An interval of zero means the exporter is not sampling.
The mode bits do not carry that meaning: many exporters that sample leave them at zero and populate the interval anyway, because the mode is not a mandatory field — pmacct's own NetFlow v5 exporter never sets it.
riptide therefore reads a non-zero interval as a rate whether or not the mode is set, which is what nfdump, pmacct, goflow2 and Akvorado all do.

If you need the old behaviour, where the header interval was ignored:

```properties
riptide.receivers.nf5.trust-header-sampling-interval=false
```

This governs only the ambiguous case, where the exporter gives an interval but leaves the mode bits at zero.
A header that states a mode *and* an interval is unambiguous and is always read, so turning this off cannot make riptide ignore an exporter that signalled properly.

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

:::warning[NetFlow v5 rates changed]
Earlier releases ignored the v5 header interval. What they recorded instead depended on the receiver:

- **No fallback configured** — every v5 flow was recorded as unsampled, `samplingInterval = 1`. Flows from an exporter that advertises a rate now carry that rate, so a query multiplying by `samplingInterval` returns a **larger** and more correct answer.
- **`flow-sampling-interval-fallback` configured** — every v5 flow carried the configured value, whatever the exporter said. The exporter's own rate now wins, so the recorded value can move in **either** direction. An exporter advertising 1:20 behind a receiver configured for 1000 drops from 1000 to 20, and a query multiplying by the rate returns an answer 50× smaller than before — and correct, where the old one was not.

Stored `bytes` and `packets` are unchanged either way, and no query errors.

Rows written before the upgrade are not rewritten: correcting them would need each exporter's rate at each past moment, which is exactly what was never recorded. A query spanning the upgrade mixes both conventions.

Find which exporters now report a rate, and when each changed:

```sql
SELECT exporterAddr, samplingInterval, min(timestamp), max(timestamp)
FROM flows
WHERE flowProtocol = 'NetflowV5'
GROUP BY exporterAddr, samplingInterval
ORDER BY exporterAddr, min(timestamp)
```

More than one row per `exporterAddr` means that exporter's recorded rate changed, and the `min(timestamp)` of the later row is when.

**If you compensated for this by hardcoding a multiplier** in a dashboard or query — because you knew riptide was not reading these exporters' rates — remove it, or you will now double-count.

To pin the old behaviour while you correct queries, set `trust-header-sampling-interval=false` on the affected receivers.
Note this only suppresses the header for exporters that leave the mode bits at zero; an exporter that states a mode is always read, so for those the old value cannot be restored by configuration.
Set `flow-sampling-interval-fallback` to the value you were relying on if you need a specific rate recorded.
:::

:::note
riptide records the sampling rate; it does not scale NetFlow or IPFIX `bytes` and `packets` by it.
Stored counters are what the exporter reported, and the rate sits alongside them for a query to apply.

Three things to know before writing that query.
sFlow already scales at ingest (`bytes = frame_length × sampling_rate`) and still reports its rate, so multiplying sFlow rows again double-counts them: filter them out, or restrict the query to NetFlow and IPFIX.
The 1-minute rollups carry neither the rate nor a scaled measure, so `SUM(bytes * samplingInterval)` only means anything against the raw `flows` table.
And if you are coming from **nfdump or pmacct**, note that both multiply NetFlow v5 `bytes` and `packets` by the rate at ingest, where riptide does not.
Counters that arrive here are unscaled, so a query ported from either will need the multiplication added rather than removed.
:::

## Exporter identity

Flows are attributed to their exporter by **source address plus observation domain**
(IPFIX, RFC 7011) or **source ID** (NetFlow v9) — two observation domains behind one
exporter IP are distinct identities. NetFlow v5 has no such concept; its engine type/ID
are mapped onto the domain. The identity drives node matching — see
[Nodes & SNMP](nodes-and-snmp.md).

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
