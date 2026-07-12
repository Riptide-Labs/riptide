---
sidebar_position: 3
title: Operations
---

# Operations notes

## Image tags

| Tag | Meaning | Use for |
|---|---|---|
| `:<version>` | immutable release | production (pin this) |
| `:latest` | newest release | quickstarts |
| `:rc` | floating, rebuilt on **every merge to main** | tracking development — at your own risk |

## Restarts and data

:::warning

Riptide issues `CREATE OR REPLACE TABLE` for its `flows` table at startup — **flow data
does not survive a Riptide restart** in the current design. Plan retention accordingly
(e.g. export/aggregate downstream) until schema migration lands.

:::

## Upgrading

Compose: `docker compose pull && docker compose up -d`. Plain JAR: replace the jar,
restart. Configuration is backward-compatible within a minor line; breaking configuration
moves are logged loudly at startup (e.g. the pre-0.1.0 `riptide.snmp.config.definitions`
tree logs an explicit error pointing at `riptide.nodes`).

## Ports

| Port | Protocol | What |
|---|---|---|
| `9999/udp` | NetFlow/IPFIX | default flow ingest (container `EXPOSE`; receivers are configurable) |
| `8123` | HTTP | ClickHouse (stack-internal unless you expose it) |
