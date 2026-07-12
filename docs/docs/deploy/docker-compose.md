---
sidebar_position: 1
title: Docker Compose
---

# Deploy with Docker Compose

The fastest way to run Riptide: a compose stack with Riptide, ClickHouse, a ClickHouse
web UI, and Grafana — using the published image, no build toolchain required.

```bash
git clone https://github.com/Riptide-Labs/riptide.git   # or copy deployment/ only
cd riptide/deployment/riptide
docker compose up -d
```

This starts, from `ghcr.io/riptide-labs/riptide:latest`:

| Service | Port | Purpose |
|---|---|---|
| riptide | `9999/udp` | flow ingest (configure your exporters to send here) |
| clickhouse | `8123`, `9000` | flow storage |
| ch-ui | [`:5521`](http://localhost:5521) | browse the `riptide.flows` table |
| grafana | [`:3000`](http://localhost:3000) | dashboards (ClickHouse datasource provisioned) |

Point a NetFlow v5/v9 or IPFIX exporter at UDP `9999` and watch rows arrive in
`riptide.flows` via ch-ui. Configure [receivers](../configuration/receivers.md) and
[nodes](../configuration/nodes-and-snmp.md) through environment variables in the compose
file (see [Plain JAR](plain-jar.md#environment-variables) for the `RIPTIDE_*` scheme) or
an external config file.

## Variants

```bash
# Track main (floating rc image, rebuilt on every merge — not for production):
docker compose -f compose.yml -f compose.override.rc.yml up -d

# Run a locally built image (after `make oci`):
docker compose -f compose.yml -f compose.override.dev.yml up -d
```

A plain `compose.override.yml` is gitignored on purpose — that's your personal,
auto-loaded slot for local tweaks (timezone, extra ports, …).
