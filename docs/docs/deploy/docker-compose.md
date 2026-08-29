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

:::warning The stack ships with default passwords

Grafana starts with `admin`/`admin` and ClickHouse's `default` user with `riptide`. Both are
fine on a laptop and not fine anywhere else. The stack publishes ports 3000, 8123 and 9000 on
every interface, so on any host with a routable address both are reachable from the network,
and the ClickHouse user holds `access_management` — it can create users and row policies.

Set your own before the first start, either in your shell or in a `.env` file in the
deployment directory:

```bash
export GF_SECURITY_ADMIN_PASSWORD='your-secure-password-here'
export CLICKHOUSE_PASSWORD='another-secure-password-here'
```

`CLICKHOUSE_PASSWORD` is read by ClickHouse, Riptide, Grafana's provisioned datasource and
ch-ui, so one value configures the whole stack. Change it and recreate the stack and all four
follow; there is no second place to edit.

`GF_SECURITY_ADMIN_PASSWORD` is only read when Grafana initialises its database. Changing it
later has no effect unless you also remove the `gf-data` volume.

:::

:::info Passwords, not firewall rules

The published ports are deliberately unchanged. Binding ClickHouse to loopback, or restricting
the `default` user by source address, breaks Riptide and Grafana: both authenticate as that
user and both reach it from a compose bridge address that varies by network. Three attempts at
the address-based fix failed that way. Authentication is what closes the hole; if you also want
the ports off the public interface, override them in a `compose.override.yml`.

:::

This starts, from `ghcr.io/riptide-labs/riptide:latest`:

| Service | Port | Purpose |
|---|---|---|
| riptide | `9999/udp` | flow ingest (configure your exporters to send here) |
| clickhouse | `8123`, `9000` | flow storage |
| ch-ui | [`:5521`](http://localhost:5521) | browse the `riptide.flows` table |
| grafana | [`:3000`](http://localhost:3000) | dashboards (ClickHouse datasource provisioned) |

Grafana ships provisioned dashboards backed by the `flows` table and the
`samples` bucket-expansion view:

- **Riptide - Top 10**: stacked top-10 rate panels (AS, hosts, applications, services, protocols,
  exporters, interfaces) plus a source-AS statistics table with a 95th-percentile column.
- **Riptide - Traffic Paths (Sankey)**: exporter- and direction-filterable path diagrams —
  AS peering (source AS → ingress → egress → destination AS), situational-awareness, geo
  origination/termination, and ultimate-exit views, weighted by bytes over the selected range.
- **Riptide - Flow Forensics**: slice flows by any combination of tenant, zone, exporter,
  application, L4 protocol, source/destination address and port — throughput of the slice, top
  hosts/conversations, protocol/DSCP/TCP-flag mix, locality matrix, and the raw records.
- **Riptide - Collection Health**: is every exporter delivering? Reporting/silent-exporter
  verdicts, a per-exporter activity timeline, collection lag percentiles, and an exporter
  inventory with drill-down into Flow Forensics.
- **Riptide - Interface Traffic Analysis**: throughput and data usage per exporter interface,
  broken out by application, conversation, host and DSCP, each as an in-vs-out pair.
- **Riptide - Capacity & Routing**: interface headroom measured against SNMP-reported link speed
  (p95 and peak as a percentage of capacity), next-hop distribution, prefix-level volume, and
  conversations only seen in one direction.
- **Riptide - Behavioural Anomalies**: scanning, host sweeps, repeated attempts against service
  ports, SYN-only ratio, fan-in targets and packet-size outliers — all derived from traffic shape
  alone, with the thresholds exposed as dashboard variables.
- **Riptide - Traffic Composition**: source/destination country maps, VLAN and DSCP mix, flow
  duration profile, IPv4-vs-IPv6 trend, prefix-length distribution, and core network services.
- **Riptide - Data Trust**: the metadata that decides whether the other dashboards can be
  believed — sampling configuration per exporter, clock corrections, tenant/organisation/zone
  labelling, and exporter identity.

The JSON sources live in `deployment/clickhouse/container-fs/grafana/provisioning/dashboards/`.
UI edits last only until the provisioned JSON changes — use *Save as* to keep a customized copy.
The dashboards are deployment-neutral: a **Datasource** variable selects the ClickHouse
connection and a **Database** variable (auto-populated from databases containing a `flows` table)
selects the riptide database, so they import into any external Grafana without a specifically
named or `defaultDatabase`-pinned datasource.

Point a NetFlow v5/v9, IPFIX or sFlow exporter at UDP `9999` and watch rows arrive in `riptide.flows` via ch-ui.
The compose file configures a single `multi` [receiver](../configuration/receivers.md) on that port.
Further settings — more [receivers](../configuration/receivers.md) or the [credential sets](../configuration/agent-configuration.md) — go through environment variables in the compose file (see [Plain JAR](plain-jar.md#environment-variables) for the `RIPTIDE_*` scheme) or an external config file. Agent ranges and [enrichment entries](../configuration/exporter-enrichment.md) live in the inventory file, which must be on the mount: it cannot be supplied through environment variables.

## Variants

```bash
# Track main (floating rc image, rebuilt on every merge — not for production):
docker compose -f compose.yml -f compose.override.rc.yml up -d

# Run a locally built image (after `make oci`):
docker compose -f compose.yml -f compose.override.dev.yml up -d
```

A plain `compose.override.yml` is gitignored on purpose — that's your personal,
auto-loaded slot for local tweaks (timezone, extra ports, …).
