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
UDP/TCP ingest (NetFlow v5 · NetFlow v9 · IPFIX)
   → decode
   → match exporter to a configured node (subnet + observation domain)
   → enrich:  SNMP interface names/aliases/speed · reverse-DNS hostnames ·
              traffic classification · locality
   → persist to ClickHouse
```

- **Flow protocols:** NetFlow v5, NetFlow v9, and IPFIX (UDP; IPFIX also via TCP).
  sFlow support is planned for v0.2.0
  ([#159](https://github.com/Riptide-Labs/riptide/issues/159)).
- **Node model:** a thin registry (`riptide.nodes[]`) matches exporters by subnet —
  optionally pinned to one observation domain — and carries the SNMP agent
  configuration used to enrich that device's flows. See
  [Nodes & SNMP](configuration/nodes-and-snmp.md).
- **Secrets:** SNMP credentials are **references** (`env://`, `file://`, `vault://`,
  `sops://`), never plaintext in configuration. See
  [Secret references](configuration/secret-references.md).
- **Enrichment:** SNMP IF-MIB (`ifName`, `ifAlias`, `ifHighSpeed`), reverse-DNS
  hostnames, rule-based classification. See [Enrichment](enrichment.md).

## Technology

Java 25 · Spring Boot · Netty · SNMP4J · ClickHouse. Licensed
[GPL-3.0-or-later](https://github.com/Riptide-Labs/riptide/blob/main/LICENSE).

## Where to go next

- [Getting started](getting-started.md) — build and run from source
- [Receivers](configuration/receivers.md) — configure flow listeners
- [Nodes & SNMP](configuration/nodes-and-snmp.md) — the node model and SNMP v1/v2c/v3
- [Secret references](configuration/secret-references.md) — Vault, SOPS, env, file
- [ClickHouse](configuration/clickhouse.md) — persistence
