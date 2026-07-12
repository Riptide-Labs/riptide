---
sidebar_position: 4
title: Enrichment
---

# Enrichment

Every flow passes an asynchronous enrichment pipeline before persistence. Enrichment
never blocks or drops flows: failures degrade to an unenriched flow with a logged
warning.

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

## Classification

Flows are classified by a rule engine (application naming). The rule source is any
Spring resource location and defaults to the bundled `classification-rules.csv`:

```properties
riptide.classification.rules=file:/etc/riptide/classification-rules.csv
```

## Locality

Source/destination/flow locality (private vs. public address space) is derived for every
flow without configuration.
