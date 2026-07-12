---
sidebar_position: 2
title: Getting started
---

# Getting started

Two paths, depending on what you're here for:

## 🚀 I want to run Riptide

Deploy the published image or jar — no build toolchain needed.

- [**Docker Compose**](deploy/docker-compose.md) — full stack (Riptide + ClickHouse +
  UI + Grafana) in one `docker compose up`
- [**Plain JAR**](deploy/plain-jar.md) — `java -jar` with file- or env-var-based
  configuration
- [Operations notes](deploy/operations.md) — image tags, restarts, upgrades

## 🛠 I want to work on Riptide

Build from source, debug locally, send a pull request.

- [**Environment**](develop/environment.md) — clone, `make`, IDE setup
- [**Run & debug**](develop/run-and-debug.md) — Riptide under a debugger with real flow
  traffic (pcap replay, nl6 simulator)
- [Testing](develop/testing.md) — unit / e2e / full-mode tiers
- [Pull requests](develop/pull-requests.md) — quality gates, DCO, commit conventions

Both paths share the [configuration reference](configuration/receivers.md) —
receivers, [nodes & SNMP](configuration/nodes-and-snmp.md),
[secret references](configuration/secret-references.md), and
[ClickHouse](configuration/clickhouse.md).
