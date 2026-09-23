---
sidebar_position: 2
title: Getting started
---

# Getting started

Two paths, depending on what you're here for:

## 🚀 I want to run Riptide

Deploy the published image or jar — no build toolchain needed.

- [**Docker Compose**](guides/docker-compose.md) — full stack (Riptide + ClickHouse +
  Grafana) in one `docker compose up`
- [**Plain JAR**](guides/plain-jar.md) — `java -jar` with file- or env-var-based
  configuration
- [**DEB / RPM packages**](guides/linux-packages.md) — `apt`/`dnf` install with a managed
  systemd service
- [**NixOS**](guides/nixos.md) — flake package plus a `services.riptide` module
- [Upgrade Riptide](guides/upgrade.md) — image tags, what the schema check migrates, per-release notes
- [Troubleshooting](operations/troubleshooting.md) — symptom, cause and fix for probes, metrics and logs

## 🛠 I want to work on Riptide

Build from source, debug locally, send a pull request.

- [**Environment**](develop/environment.md) — clone, `make`, IDE setup
- [**Run & debug**](develop/run-and-debug.md) — Riptide under a debugger with real flow
  traffic (pcap replay, nl6 simulator)
- [Testing](develop/testing.md) — unit / e2e / full-mode tiers
- [Pull requests](develop/pull-requests.md) — quality gates, DCO, commit conventions

Both paths share the [reference section](reference/receivers.md) —
receivers, [SNMP agents](reference/agent-configuration.md),
[secret references](reference/secret-references.md), and
[ClickHouse](reference/clickhouse.md).
