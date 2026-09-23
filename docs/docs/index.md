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
  TCP). See [Receivers](reference/receivers.md).
- **Inventory model:** named credential sets and polling profiles in the main config; agent ranges in a hot-reloaded inventory file; exporter enrichment entries in that same file.
  A device inside a credentialed range is polled from its first flow, with no per-device configuration.
  See [SNMP agents](reference/agent-configuration.md) and [Exporter enrichment](reference/exporter-enrichment.md).
- **Dynamic discovery:** exporter enrichment entries can come from an external source of truth instead of that file.
  Three sources ship: a Prometheus HTTP service discovery document, NetBox's device API with nothing installed on it, or any JSON endpoint mapped by paths you write.
  Discovery stays off until `riptide.discovery.url` is set.
  Until then the inventory file owns both trees.
  See [Dynamic discovery](reference/discovery.md).
- **Secrets:** SNMP credentials are **references** (`env://`, `file://`, `vault://`,
  `sops://`), never plaintext in configuration. See
  [Secret references](reference/secret-references.md).
- **Enrichment:** a graceful-degradation ladder — rule-based classification, exporter
  clock correction, locality, AS data from the routing mapping, GeoIP country/city
  (MaxMind GeoLite2 or IPinfo, with per-prefix overrides), SNMP IF-MIB interface data,
  exporter-pushed option records, reverse-DNS hostnames. Flows persist even when every
  live source is unreachable. See [Enrichment](architecture/enrichment.md) and
  [GeoIP](reference/geoip.md).
- **Multi-tenancy:** every flow carries tenant/organisation/zone/system identity;
  `riptide onboard` provisions role-based ClickHouse access with hard row-level
  isolation per tenant. See [Multi-tenancy](architecture/multi-tenancy.md) and
  [Onboard a tenant](guides/onboard-a-tenant.md).
- **AI Agent Integration:** native embedded MCP server (`org.riptide.mcp.*`) over stdio IPC and HTTP/SSE with 7 auto-shipped Agent Skills (`/riptide-investigate-ddos`, `/riptide-cause-analysis`, etc.) and `SecretRef` token authentication. See [MCP Server](reference/mcp-server.md).

## Technology

Java 25 · Spring Boot · Netty · SNMP4J · ClickHouse. Licensed
[GPL-3.0-or-later](https://github.com/Riptide-Labs/riptide/blob/main/LICENSE).

## Where to go next

- 🚀 [Deploy Riptide](guides/docker-compose.md) — run the published image (Compose, plain JAR, packages, NixOS)
- 🛠 [Develop & Contribute](develop/environment.md) — build, debug, test, send PRs
- [Guides](guides/upgrade.md) — upgrade, hot reload, profiling, classification rules, rollups, tenants, discovery
- [Architecture](architecture/enrichment.md) — how enrichment, sampling, persistence, rollups, reloads and multi-tenancy work
- [Reference](reference/receivers.md) — every setting, subcommand flag, endpoint and metric
- [Operations](operations/troubleshooting.md) — troubleshooting, rollup drift, dead letters
