---
sidebar_position: 1
slug: /
title: Overview
---

# 🌊 Riptide

Riptide is a **NetFlow analysis engine**: it ingests flow telemetry from network devices,
enriches every flow record with network context, and persists the result to ClickHouse for
analysis.

## What it does

```
UDP/TCP ingest (NetFlow v5 · NetFlow v9 · IPFIX · sFlow)
   → decode
   → match exporter to a configured node (subnet + observation domain)
   → enrich:  classification · clock correction · locality ·
              AS numbers/orgs · GeoIP country/city ·
              SNMP interface names/aliases/speed · reverse-DNS hostnames
   → persist to ClickHouse (tenant/organisation/zone/system identity)
```

- **Flow protocols:** NetFlow v5, NetFlow v9, IPFIX, and sFlow (UDP; IPFIX also via
  TCP). See [Receivers](configuration/receivers.md).
- **Node model:** a thin registry (`riptide.nodes.<name>`) matches exporters by subnet —
  optionally pinned to one observation domain — and carries the SNMP agent
  configuration used to enrich that device's flows. See
  [Nodes & SNMP](configuration/nodes-and-snmp.md).
- **Secrets:** SNMP credentials are **references** (`env://`, `file://`, `vault://`,
  `sops://`), never plaintext in configuration. See
  [Secret references](configuration/secret-references.md).
- **Enrichment:** a graceful-degradation ladder — rule-based classification, exporter
  clock correction, locality, AS data from the routing mapping, GeoIP country/city
  (MaxMind GeoLite2 or IPinfo, with per-prefix overrides), SNMP IF-MIB interface data,
  exporter-pushed option records, reverse-DNS hostnames. Flows persist even when every
  live source is unreachable. See [Enrichment](enrichment.md) and
  [GeoIP](configuration/geoip.md).
- **Multi-tenancy:** every flow carries tenant/organisation/zone/system identity;
  `riptide onboard` provisions role-based ClickHouse access with hard row-level
  isolation per tenant. See the
  [multi-tenancy runbook](deploy/multi-tenancy.md).

## Technology

Java 25 · Spring Boot · Netty · SNMP4J · ClickHouse. Licensed
[GPL-3.0-or-later](https://github.com/Riptide-Labs/riptide/blob/main/LICENSE).

## Where to go next

- 🚀 [Deploy Riptide](deploy/docker-compose.md) — run the published image (Compose or plain JAR)
- 🛠 [Develop & Contribute](develop/environment.md) — build, debug, test, send PRs
- [Receivers](configuration/receivers.md) — configure flow listeners
- [Nodes & SNMP](configuration/nodes-and-snmp.md) — the node model and SNMP v1/v2c/v3
- [Secret references](configuration/secret-references.md) — Vault, SOPS, env, file
- [ClickHouse](configuration/clickhouse.md) — persistence
