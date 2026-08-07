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
riptide reads the mode from that field and reports it as the sampling algorithm, but **does not yet read the interval** — so a sampling v5 exporter is recorded as unsampled unless you name the rate yourself.

For an exporter that never advertises a rate, name it yourself:

```properties
riptide.receivers.nf9.flow-sampling-interval-fallback=1000
```

For NetFlow v9 and IPFIX this is a last resort, not an override.
What the exporter says always wins: a rate on the flow record first, then the rate from its sampler options table, then this setting, then unsampled.
An exporter that explicitly reports an interval of 1 has said it does not sample, and that answer stands over the fallback.

For **NetFlow v5** the same setting is the *only* rung above unsampled rather than a last resort, because the rate a v5 exporter states in its header is not yet read.
It applies to a dedicated `netflow5` receiver and to the v5 half of a `multi` receiver alike:

```properties
riptide.receivers.nf5.type=netflow5
riptide.receivers.nf5.flow-sampling-interval-fallback=1000
```

:::caution
Because the v5 header interval is not read yet, this setting is applied to v5 flows even when the exporter's header states a mode and an interval of its own.
For v5 only, the fallback is therefore an override in practice, which is the opposite of how it behaves for v9 and IPFIX.
If your v5 exporters set the sampling mode bits, check what they advertise before configuring a rate here.
:::

Note the fallback applies to the whole receiver, so exporters sharing a port share it.
That matters most for v5, where it is doing all the work: a receiver fronting a mixed fleet gives every v5 exporter behind it the same rate.
Give exporters that sample at different rates their own receivers and ports.

:::note
riptide records the sampling rate; it does not scale NetFlow or IPFIX `bytes` and `packets` by it.
Stored counters are what the exporter reported, and the rate sits alongside them for a query to apply.

Two things to know before writing that query.
sFlow already scales at ingest (`bytes = frame_length × sampling_rate`) and still reports its rate, so multiplying sFlow rows again double-counts them: filter them out, or restrict the query to NetFlow and IPFIX.
And the 1-minute rollups carry neither the rate nor a scaled measure, so `SUM(bytes * samplingInterval)` only means anything against the raw `flows` table.
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
