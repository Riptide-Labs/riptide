---
sidebar_position: 1
slug: /
title: Overview
description: What riptide does, a quickstart from an empty host to flow rows in ClickHouse, and where to go next to deploy, configure or develop it.
---

# 🌊 Riptide

Riptide is a NetFlow analysis engine.
It ingests flow telemetry from network devices, enriches every flow record with network context, and persists the result to ClickHouse for analysis.

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

- **Flow protocols:** NetFlow v5, NetFlow v9, IPFIX and sFlow over UDP, and IPFIX also over TCP.
  See [Receivers](reference/receivers.md).
- **Inventory model:** named credential sets and polling profiles in the main config; agent ranges in a hot-reloaded inventory file; exporter enrichment entries in that same file.
  A device inside a credentialed range is polled from its first flow, with no per-device configuration.
  See [SNMP agents](reference/agent-configuration.md) and [Exporter enrichment](reference/exporter-enrichment.md).
- **Dynamic discovery:** exporter enrichment entries can come from an external source of truth instead of that file.
  Three sources ship: a Prometheus HTTP service discovery document, NetBox's device API with nothing installed on it, or any JSON endpoint mapped by paths you write.
  Discovery stays off until `riptide.discovery.url` or `riptide.discovery.urls` is set.
  Until then the inventory file owns both trees.
  See [Dynamic discovery](reference/discovery.md).
- **Secrets:** SNMP credentials are references (`env://`, `file://`, `vault://`, `sops://`), never plaintext in configuration.
  See [Secret references](reference/secret-references.md).
- **Enrichment:** a graceful-degradation ladder.
  It covers rule-based classification, exporter clock correction, locality, AS data from the routing mapping, GeoIP country/city (MaxMind GeoLite2 or IPinfo, with per-prefix overrides), SNMP IF-MIB interface data, exporter-pushed option records and reverse-DNS hostnames.
  Flows persist even when every live source is unreachable.
  See [Enrichment](architecture/enrichment.md) and [GeoIP](reference/geoip.md).
- **Multi-tenancy:** every flow carries tenant/organisation/zone/system identity.
  `riptide onboard` provisions role-based ClickHouse access with hard row-level isolation per tenant.
  See [Multi-tenancy](architecture/multi-tenancy.md) and [Onboard a tenant](operations/tenants/onboard-a-tenant.md).
- **AI Agent Integration:** native embedded MCP server (`org.riptide.mcp.*`) over stdio IPC and HTTP/SSE with 7 auto-shipped Agent Skills (`/riptide-investigate-ddos`, `/riptide-cause-analysis`, etc.) and `SecretRef` token authentication.
  See [MCP Server](reference/mcp-server.md).

## Quickstart {/* #quickstart */}

Start the shipped Docker Compose stack, send it one NetFlow v5 datagram with three flow records, and count the three rows it writes to `riptide.flows`.

### Prerequisites

- Docker with the Compose plugin, Git and Python 3.
- Ports free on the host: `9999/udp`, `3000/tcp`, and `8123/tcp` and `9000/tcp` on loopback.

### Steps

1. Get the stack files and set the two passwords in a `.env` file next to `compose.yml`:

   ```bash
   git clone https://github.com/Riptide-Labs/riptide.git
   cd riptide/deployment/riptide
   cat > .env <<'EOF'
   CLICKHOUSE_PASSWORD=change-me-before-anyone-else-can-reach-this-host
   GF_SECURITY_ADMIN_PASSWORD=change-me-too
   EOF
   ```

2. Start the stack and check that every service reports healthy:

   ```bash
   docker compose up -d
   docker compose ps --format 'table {{.Service}}\t{{.Status}}'
   ```

   Expected output, after the image pull:

   ```text
   [+] up 7/7
    ✔ Network riptide_default             Created                              0.0s
    ✔ Volume riptide_clickhouse-data      Created                              0.0s
    ✔ Volume riptide_gf-data              Created                              0.0s
    ✔ Container riptide-clickhouse-1      Healthy                              4.7s
    ✔ Container riptide-riptide-1         Started                              4.8s
    ✔ Container riptide-grafana-1         Healthy                             15.3s
    ✔ Container riptide-grafana-folders-1 Started                             15.4s
   SERVICE      STATUS
   clickhouse   Up 19 seconds (healthy)
   grafana      Up 14 seconds (healthy)
   riptide      Up 14 seconds (healthy)
   ```

   A fourth row, `grafana-folders`, shows for the few seconds that one-shot container takes to nest the dashboard folder in Grafana, and then leaves the list.
   If `riptide` still shows `(health: starting)`, run the `ps` command again after a few seconds.

3. Send one NetFlow v5 datagram to the collector.
   It carries three flow records from `192.0.2.1` to `198.51.100.1`, `.2` and `.3`, timestamped now.
   Both ranges are reserved for documentation, so nothing routable lands in your table.

   ```bash
   python3 -c '
   import socket, struct, time
   recs = b"".join(struct.pack("!4s4s4sHHIIIIHHBBBBHHBBH", socket.inet_aton("192.0.2.1"), socket.inet_aton("198.51.100.%d" % i), bytes(4), 1, 2, 10, 1500, 90000, 99000, 40000 + i, 443, 0, 0x18, 6, 0, 0, 0, 24, 24, 0) for i in (1, 2, 3))
   hdr = struct.pack("!HHIIIIBBH", 5, 3, 100000, int(time.time()), 0, 0, 0, 0, 0)
   print(socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(hdr + recs, ("127.0.0.1", 9999)), "bytes sent")
   '
   ```

   Expected output, a 24-byte header plus three 48-byte records:

   ```text
   168 bytes sent
   ```

4. Count the rows from that source.
   Riptide inserts in batches of up to two seconds, so wait that long first.

   ```bash
   docker compose exec -T clickhouse sh -c 'clickhouse-client --password "$CLICKHOUSE_PASSWORD"' <<'SQL'
   SELECT count() FROM riptide.flows WHERE srcAddr = toIPv6('192.0.2.1')
   SQL
   ```

   Expected output:

   ```text
   3
   ```

   Each run of step 3 adds three more rows, so a second run makes the count `6`.
   A `0` means the datagram never reached the collector: check that nothing else holds UDP port 9999, and read `docker compose logs riptide`.

Grafana is at `http://localhost:3000`, user `admin`, with the riptide dashboards provisioned.
To send real traffic, point a NetFlow v5, NetFlow v9, IPFIX or sFlow exporter at UDP port `9999` of the host.
[Docker Compose](guides/docker-compose.md) covers what the stack runs, how its passwords behave, reaching ClickHouse from another host and the image variants.
`docker compose down -v` removes the stack and its data.

## Where to go next

Run riptide in production:

- [Docker Compose](guides/docker-compose.md): the stack from the quickstart, in full.
- [Plain JAR](guides/plain-jar.md): `java -jar` with file- or env-var-based configuration.
- [DEB and RPM packages](guides/linux-packages.md): `apt` or `dnf` install with a managed systemd service.
- [NixOS](guides/nixos.md): flake package plus a `services.riptide` module.
- [Upgrade riptide](operations/upgrades/upgrade.md): image tags, what the schema check migrates, per-release notes.

Work on riptide:

- [Environment](develop/environment.md): clone, `make`, IDE setup.
- [Run and debug](develop/run-and-debug.md): riptide under a debugger with real flow traffic from a pcap replay or the nl6 simulator.
- [Testing](develop/testing.md): unit, e2e and full-mode tiers.
- [Pull requests](develop/pull-requests.md): quality gates, DCO, commit conventions.

Look something up:

- [Guides](guides/docker-compose.md): deploy riptide, discover exporters from an inventory, query flow volumes and rollups.
- [Architecture](architecture/enrichment.md): how enrichment, sampling, persistence, rollups, reloads and multi-tenancy work.
- [Reference](reference/receivers.md): every setting, subcommand flag, endpoint and metric.
- [Operations](operations/troubleshooting.md): troubleshooting, rollup drift, dead letters, hot reload, profiling, classification rules, tenants and upgrades.

## Technology

Java 25 · Spring Boot · Netty · SNMP4J · ClickHouse.
Licensed [GPL-3.0-or-later](https://github.com/Riptide-Labs/riptide/blob/main/LICENSE).
