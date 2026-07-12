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
| 1 — static | operator mapping files (node `interfaces`, routing mapping) | a config file |
| 0 — packet | ifIndex numbers, exporter-sent AS numbers, addresses, next hop | nothing — always available |

**Precedence is per-field pin**: a field set in a static mapping overrides the live
value; live sources fill the fields the file doesn't set; packet data is the floor.
For AS numbers, a **nonzero exporter-provided value always wins** — the routing mapping
only fills zeros.

## Static interface mapping

A node may carry its own interface table — the middle rung, for devices without
(reachable) SNMP:

```yaml
riptide:
  nodes:
    core-router:
      subnet-address: 10.20.30.0/24
      interfaces:
        "10": { name: eth0, alias: "Uplink to AS64500", high-speed: 10000 }
        "12": { name: eth2 }
```

With an `snmp` block present too, file fields pin and SNMP fills the rest — e.g. a
pinned `alias` with live `name`/`high-speed`. `high-speed` is Mbit/s, matching
`ifHighSpeed`.

## SNMP interface data

When a flow's exporter matches a [node](configuration/nodes-and-snmp.md) with SNMP
configuration, the numeric `ifIndex` values carried by the flow (`INPUT_SNMP` /
`OUTPUT_SNMP` in NetFlow v9; `ingressInterface` / `egressInterface` in IPFIX) are
resolved against the device's IF-MIB:

| Resolved | IF-MIB source | Notes |
|---|---|---|
| `…IfName` | `ifName` (ifXTable), `ifDescr` fallback (legacy ifTable) | short interface name, e.g. `Eth1/0` |
| `…IfAlias` | `ifAlias` (ifXTable) | the operator-assigned label; unlike `ifIndex` it is stable across device reboots (RFC 2863) |
| `…IfSpeed` | `ifHighSpeed` (ifXTable) | Mbit/s |

Results are cached per `(poll address, ifIndex)` with a configurable retention:

```properties
riptide.snmp.cache.retentionMs=600000
```

The retention is an absolute staleness bound (`expireAfterWrite`) — the backstop for
`ifIndex` reassignment after device reboots.

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

## Locality

Source/destination/flow locality (private vs. public address space) is derived for every
flow without configuration.
