---
sidebar_position: 6
title: Enrichment
---

# Enrichment

Every flow passes an asynchronous enrichment pipeline before persistence. Enrichment
never blocks or drops flows: failures degrade to an unenriched flow with a logged
warning.

## The enrichment ladder

Riptide enriches each flow as well as the environment allows and **degrades
gracefully** — in the worst case a flow carries exactly what the packets said:

| Layer | Source | Needs |
|---|---|---|
| 2 — live | SNMP IF-MIB, reverse DNS | reachable agents/resolvers |
| 1.5 — exporter-pushed | v9/IPFIX interface option records (`option interface-table`) | the exporter sending them — nothing on riptide's side |
| 1 — static | operator mapping files (enrichment-entry `interfaces`, routing mapping) | a config file |
| 0.5 — global databases | GeoIP mmdb files ([`riptide.geoip`](configuration/geoip.md)) | database files on disk |
| 0 — packet | ifIndex numbers, exporter-sent AS numbers, addresses, next hop | nothing — always available |

**Precedence is per-field pin**: a field set in a static mapping overrides the live
value; live sources fill the fields the file doesn't set; packet data is the floor.
For AS numbers, a **nonzero exporter-provided value always wins** — the routing mapping
only fills zeros, and GeoIP databases sit below the routing mapping (exporter →
routing prefixes → geoip override → geoip databases). Country and city come only from
GeoIP; a [`riptide.geoip.overrides`](configuration/geoip.md#manual-overrides) entry pins
its set fields over whatever the databases resolve.

For interface fields, exporter-pushed option data and live SNMP share the work with
**per-field authority** (after any static pin): the interface **name** prefers the
option record (IE 82 is exactly ifName, and pushed data is fresher than a poll); the
**alias** prefers SNMP ifAlias — IE 83 (`interfaceDescription`) may carry ifDescr- or
ifAlias-style content depending on the vendor, so it only fills the alias when SNMP
can't; the **speed** exists only in SNMP. Cisco IOS-XR exporters send their interface
table with descriptions only (no IE 82) — those flows get aliases without any SNMP
configuration.

The floor extends into parsing: an sFlow sample whose raw packet header cannot be
decoded (truncated by the sampler, non-IP payload) still becomes a flow carrying the
sample-level data — bytes, packets, interfaces — with the undecodable fields simply
absent. Undecodable is not an error.

## Static interface mapping

An [enrichment entry](configuration/exporter-enrichment.md) may carry its own interface
table — the middle rung, for devices without (reachable) SNMP:

```yaml
riptide:
  exporters:
    core-router:
      address: 10.20.30.0/24
      interfaces:
        "10": { name: eth0, alias: "Uplink to AS64500", high-speed: 10000 }
        "12": { name: eth2 }
```

With the device also inside a credentialed [agent range](configuration/agent-configuration.md),
pinned fields win and SNMP fills the rest — e.g. a pinned `alias` with live
`name`/`high-speed`. `high-speed` is Mbit/s, matching `ifHighSpeed`.

## SNMP interface data

When a flow's exporter falls inside a credentialed [agent range](configuration/agent-configuration.md) with SNMP
configuration, the numeric `ifIndex` values carried by the flow (`INPUT_SNMP` /
`OUTPUT_SNMP` in NetFlow v9; `ingressInterface` / `egressInterface` in IPFIX) are
resolved against the device's IF-MIB:

| Resolved | IF-MIB source | Notes |
|---|---|---|
| `…IfName` | `ifName` (ifXTable), `ifDescr` fallback (legacy ifTable) | short interface name, e.g. `Eth1/0` |
| `…IfAlias` | `ifAlias` (ifXTable) | the operator-assigned label; unlike `ifIndex` it is stable across device reboots (RFC 2863) |
| `…IfSpeed` | `ifHighSpeed` (ifXTable) | Mbit/s |

### Interface tables are polled, not looked up

Riptide **never issues SNMP on the flow path**. An exporter is registered the first time a flow arrives from it, its whole interface table is then walked on a schedule, and enrichment reads the resulting snapshot.

That is the operator-visible reason exporter CPU drops after upgrading: load on a device's SNMP agent is now a function of the poll schedule rather than of how many distinct interfaces its flows happen to reference. Previously each `(exporter, ifIndex)` pair cost its own full table walk, so a busy device with many active interfaces was polled hardest — and walks for different interfaces on the same device could run at the same time.

```properties
riptide.snmp.polling.default.refresh-interval=10m   # how often each exporter is walked
riptide.snmp.polling.default.snapshot-expiry=30m    # how long a snapshot stays usable
riptide.snmp.poll.pool-width=4                   # walks in flight across the whole fleet
riptide.snmp.poll.deregister-after=3             # silent refresh intervals before polling stops
riptide.snmp.poll.dead-endpoint-base-ms=60000    # first retry delay after a failed walk
riptide.snmp.poll.dead-endpoint-ceiling-ms=1800000
riptide.snmp.poll.max-exporters=4096             # bound on retained snapshots
```

Cadence (refresh and expiry) is per polling profile: define profiles under `riptide.snmp.polling.<name>` and reference them from agent ranges in the inventory file.
The profile named `default` applies to every range that names none; without one, built-in defaults (10 m refresh, 30 m expiry) apply.
The retired global keys `riptide.snmp.poll.refresh-interval-ms` and `riptide.snmp.poll.snapshot-expiry-ms` fail startup if set, in any file or environment spelling.
The remaining `riptide.snmp.poll.*` keys above are fleet-level and keep binding as before.

**Refresh and expiry are two settings because they answer two questions.**
Refresh is how fresh the data is kept.
Expiry is the absolute staleness bound — the backstop for `ifIndex` reassignment after a device reboot (RFC 2863).
A snapshot older than the refresh interval but inside the expiry window is **still served**, because an interface name from the previous cycle beats no interface name at all.
Setting expiry shorter than refresh makes enrichment blank between walks, and the collector warns at startup if you do.

Walks are spread across the refresh interval using a phase derived from the exporter's address, so the fleet does not arrive at the agent as one burst, and the phase is stable across restarts without any stored state.

:::info[Expect a warmup window]

Between an exporter's first flow and its first completed walk there is no snapshot, so those flows carry **no SNMP-derived interface fields**.
Static interface pins and exporter-pushed option data still apply, so enrichment degrades rather than fails.
This is expected behaviour, not a fault — it is the cost of never blocking flow processing on a network round trip.

A newly added interface likewise becomes visible at the next poll rather than within a minute.
An unresolvable `ifIndex` deliberately does **not** trigger an early walk, because that would put agent load back under the control of flow traffic.

:::

### Migrating from `riptide.snmp.cache.*`

| Retired | Replacement | Note |
|---|---|---|
| `riptide.snmp.cache.retention-ms` | `riptide.snmp.polling.<name>.refresh-interval` | **Not carried over automatically.** The old value was a cache TTL (how long an answer stays usable); the new one is a poll interval (how often to ask). Adopting a 60 s retention would mean walking every exporter every minute — ten times the agent load, silently. Set it deliberately, in a polling profile. |
| `riptide.snmp.cache.negative-retention-ms` | *(none)* | Misses are no longer cached separately: an `ifIndex` absent from a polled snapshot is a known absence, so there is nothing to expire. |
| `riptide.snmp.cache.dead-endpoint-retention-ms` | `riptide.snmp.poll.dead-endpoint-base-ms` / `-ceiling-ms` | Unreachable endpoints now back off exponentially instead of retrying at a fixed interval. |

Riptide logs a warning at startup for each retired property it finds set, so a stale configuration file is loud rather than silently ineffective.

The exporter option table keeps its own retention, now named for what it is:

```properties
riptide.snmp.options.retention-ms=1200000
```

## Reverse-DNS hostnames

Source, destination, and next-hop addresses are resolved to hostnames via PTR lookups
(Netty-based, asynchronous):

```properties
riptide.enricher.hostnames.enabled=true
```

The enricher is on unless disabled; the bundled `application.properties` ships with it
set to `false`.

## AS numbers and names

The static [routing mapping](configuration/routing.md) fills `srcAs`/`dstAs` when the
exporter sent zeros (nonzero exporter values always win) and resolves AS names/orgs
into `srcAsOrg`/`dstAsOrg`.

## Classification

Flows are classified by a rule engine (application naming). The rule source is any
Spring resource location and defaults to the bundled `classification-rules.csv`:

```properties
riptide.classification.rules=file:/etc/riptide/classification-rules.csv
```

Row order is the evaluation priority: when several rules match a flow — common when a
client's ephemeral port collides with another rule's registered port — the earliest
matching row wins, in both directions of an omnidirectional rule. In a custom ruleset,
put specific rules (address + port) above broad ones (port-only), or the broad row will
shadow them.

### Writing a rule

The header is fixed and every column must be present, in this order:

```csv
name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional
ssh;tcp;;;;22;;true
dns;tcp,udp;;;;53;;true
mgmt;;10.0.0.0/8;;;;;false
```

Two rules about the condition columns, because getting either wrong used to fail quietly:

- **An empty column means "any".** That is the only way to say it — there is no wildcard.
  A column that is filled in but names nothing Riptide can resolve gets the whole rule
  **rejected**: it classifies nothing, and the reload log names it. That covers a typo
  (`tpc`), a stray `,`, a `*`, and a protocol written as a number.
- **Name protocols by keyword, not by number** — `tcp`, not `6`. The keywords are IANA's,
  with two Riptide still accepts under the older name: **55 is `MOBILE`** (IANA renamed it
  `Min-IPv4`) and **84 accepts `TTP` as well as `IPTM`**. One bad keyword refuses the whole
  rule, so `tcp,tpc` is refused rather than quietly narrowed to `tcp`.

`exporterFilter` must be left **empty**. The column is part of the required header and
cannot be removed, but nothing evaluates a value in it, so a rule carrying one is rejected
rather than silently applied to every exporter. Per-exporter scoping does not exist today.

Watch the log after changing a ruleset: a rejected rule is **not** a failed reload, so the
rest of the ruleset keeps serving and no metric moves. The WARN naming the rule is the only
signal, and the ERROR beside it names the column and the offending value. See
[Operations](deploy/operations.md) for the reload semantics in full.

The rules resource is parsed once while the context starts — an unreadable or unparseable resource fails the boot there — and then loaded into the engine's decision tree on a background thread.
Nothing re-reads the resource afterwards unless you ask for it:

```properties
riptide.classification.reload-interval=5m   # absent or 0 = disabled (the default)
```

With an interval, the resource is polled on that schedule and a changed ruleset — a local file or an `http(s)://` endpoint — classifies without a restart; unchanged bytes rebuild nothing.
A fetch that fails and a ruleset that will not parse both keep the last good rules classifying.
An `http(s)://` location works the same way, which is how one ruleset serves a fleet without shipping a file to every host:

```properties
riptide.classification.rules=https://rules.internal/riptide.csv
```

A remote ruleset carries two costs worth knowing before you choose it.
The endpoint is fetched eagerly at startup, so a rules server that is down usually keeps the collector from coming up.
And nothing authenticates the fetch, so protect the endpoint at the network layer.
The schedule, the `classification.reload.*` metric family and the one narrow startup case that leaves classification unavailable with the process up are described under [classification rule reloads](deploy/operations.md#classification-rule-reloads).

## Locality

Source/destination/flow locality (private vs. public address space) is derived for every
flow without configuration.

## Clock correction

Exporter clocks lie: sysUpTime arithmetic produces impossible timestamp orderings, and a
device with broken NTP exports flows minutes in the past or future — invisible in any
"last 15 minutes" dashboard window even though they arrive and persist fine. Clock
correction defends the flow's time columns with two mechanisms:

1. **Ordering repair** (always on): a flow claiming `firstSwitched` *after*
   `lastSwitched` is rebuilt anchored on the packet's export timestamp, preserving the
   flow's duration where the record allows.
2. **Skew correction** (opt-in): the export timestamp is compared against `receivedAt` —
   the collector's own clock. When the difference reaches the threshold, *all* of the
   flow's time columns (`timestamp`, `firstSwitched`, `deltaSwitched`, `lastSwitched`)
   are shifted by the negative skew, so the flow lands where it actually happened.

```properties
riptide.enricher.clock-correction.enabled=true
# 0 (default) disables skew correction — only the ordering repair runs.
riptide.enricher.clock-correction.skew-threshold-ms=120000
```

Every applied skew correction is recorded in the flow's `clockCorrection` column
(the negated skew), so corrections are auditable per row — and a skewed exporter is
queryable directly:

```sql
SELECT exporterAddr, count() AS correctedFlows
FROM flows WHERE clockCorrection != 0 GROUP BY exporterAddr
```

Choose the threshold above your fleet's normal export delay: exporters typically run
one to two active-timeout intervals behind `receivedAt` (often 60–90s), so a threshold
of 2 minutes corrects genuinely broken clocks without rewriting healthy jitter. Skew
correction trusts the collector's clock — keep the riptide host NTP-synced, or the
"correction" would skew every exporter by the collector's own error. Fixing the
device's NTP remains the real cure; this is the safety net that keeps the data usable
until it lands.
