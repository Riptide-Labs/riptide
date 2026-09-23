---
sidebar_position: 2
title: Run & debug
description: Start ClickHouse and Grafana from the shipped stack, run riptide from the IDE or the jar with one receiver, replay a capture into it, and read the rows back.
---

# Run and debug locally

## Prerequisites

- A build from [Set up a development environment](environment.md).
- Docker with the Compose plugin.
- Python 3 for the replay script.
- A capture of exporter traffic in classic libpcap format: NetFlow v5, NetFlow v9, IPFIX or sFlow over UDP.
  Convert a pcapng file first with `tcpdump -r in.pcapng -w out.pcap`.
  Without a capture, the [nl6 simulator](#simulate-a-network-with-nl6) generates the traffic.

## Steps

1. Start ClickHouse and Grafana:

   ```bash
   docker compose -f deployment/clickhouse/compose.yml up -d
   ```

   Expected output on the first start:

   ```text
    Network clickhouse_default Created
    Container clickhouse-clickhouse-1 Created
    Container clickhouse-grafana-1 Created
    Container clickhouse-clickhouse-1 Started
    Container clickhouse-clickhouse-1 Healthy
    Container clickhouse-grafana-1 Started
   ```

   ClickHouse is published on `127.0.0.1:8123`, which riptide's default endpoint `http://localhost:8123` reaches, as user `default` with password `riptide`.
   Grafana is at `http://localhost:3000`, user `admin`, password `admin`, and its port is published on every interface.
   Set **`CLICKHOUSE_PASSWORD`** or **`GF_SECURITY_ADMIN_PASSWORD`** in the environment before `up` to change either.
   This is the ClickHouse and Grafana half of the [shipped stack](../deploy/docker-compose.md#what-the-stack-runs), without the riptide container.

2. Start riptide with one receiver.
   From the IDE, run `org.riptide.RiptideApplication` with the arguments below as program arguments.
   From a shell:

   ```bash
   java -jar target/riptide-flows-*.jar \
     --riptide.clickhouse.password=riptide \
     --riptide.receivers.ipfix.type=ipfix \
     --riptide.receivers.ipfix.host=127.0.0.1 \
     --riptide.receivers.ipfix.port=9999
   ```

   Expected output, the lines that matter:

   ```text
   o.riptide.management.ManagementServer    : Management server listening on 0.0.0.0:8080 (/livez, /readyz, /metrics)
   org.riptide.RiptideApplication           : Started RiptideApplication in 1.612 seconds (process running for 1.971)
   org.riptide.flows.Daemon                 : Receiver 'ipfix' listening on UDP 127.0.0.1:9999
   org.riptide.flows.Daemon                 : Listening for flows with 1 receivers \o/
   ```

   No receiver is configured by default.
   Match `type` to the capture: `netflow5`, `netflow9`, `ipfix`, `sflow`, or `multi` for any of them on one port; see [Receivers](../configuration/receivers.md).
   Set breakpoints in the parsers under `org.riptide.flows.parser`, the enrichers under `org.riptide.snmp`, `org.riptide.dns`, `org.riptide.routing` and `org.riptide.geoip`, or the pipeline under `org.riptide.pipeline`.

3. Replay the capture.
   Every UDP payload goes to the receiver's port, whatever port the exporter used in the capture:

   ```bash
   python3 contrib/reply-pcap.py capture.pcap
   ```

   Expected output, for a 595-datagram IPFIX capture:

   ```text
   sent 595 UDP payloads to 127.0.0.1:9999; skipped 0
   ```

4. Read the rows back:

   ```bash
   docker compose -f deployment/clickhouse/compose.yml exec clickhouse \
     clickhouse-client --password riptide -q \
     "SELECT count() FROM riptide.flows WHERE receivedAt > now() - INTERVAL 1 MINUTE"
   ```

   Expected output, for the same capture:

   ```text
   1302
   ```

   Grafana's Explore view runs the same query against the provisioned datasource.

## Replay options

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`--host`** | address | `127.0.0.1` | Where the collector listens. |
| **`--port`** | int | `9999` | The collector's UDP port. |
| **`--delay`** | seconds | `0.01` | Pause between sends. `0` disables pacing. |
| **`--match-port`** | int | unset | Send only datagrams whose destination port in the capture is this. |

The script prints its summary and refusal lines on stderr.

Exit codes:

| Code | Meaning |
| --- | --- |
| `0` | The file was read; the summary line counts what was sent and what was skipped, by reason. |
| `2` | The file was refused and nothing was sent. |

```bash
python3 contrib/reply-pcap.py capture.pcapng
```

Expected output:

```text
refused: this is a pcapng file; convert it first: tcpdump -r in.pcapng -w out.pcap
```

```bash
python3 contrib/reply-pcap.py capture.pcap --match-port 2055
```

Expected output when no datagram in the capture went to port 2055:

```text
sent 0 UDP payloads to 127.0.0.1:9999; skipped 595 (port mismatch 595)
```

## What the script reads

| Outcome | Applies to |
| --- | --- |
| Sent | IPv4 UDP datagrams in Ethernet frames (one 802.1Q tag allowed) or Linux cooked v1 and v2 frames, from a libpcap file with a microsecond or nanosecond magic in either byte order. |
| Skipped and counted by reason | IPv6, anything else that is not IPv4, non-UDP, IP fragments, datagrams truncated by the capture's snapshot length, a malformed UDP length, and a port mismatch under `--match-port`. |
| Refused, exit `2` | pcapng, an unknown magic, a file shorter than the 24-byte libpcap header, and a link type other than the three above. |

## What a replay does not reproduce

- **The exporter is `127.0.0.1`.**
  The collector sees the replay host, not the capture's source address, so [agent enrichment](../configuration/agent-configuration.md) keyed on the real exporter does not apply unless the inventory maps the loopback address.
- **Flow timestamps are the capture's.**
  `timestamp`, `firstSwitched` and `lastSwitched` carry the capture's clock; only `receivedAt` is the replay time.
  The provisioned dashboards filter on `timestamp`, except the collection-health panels keyed on `receivedAt`, so a "Last 15 minutes" window shows nothing.
  Widen it to the capture's time.
- **Templates must come first.**
  A NetFlow v9 or IPFIX capture that starts mid-stream replays data records the collector drops until the next template refresh in the capture.
- **Several exporters collapse into one session.**
  Riptide still tells them apart by observation domain (IPFIX) or source ID (NetFlow v9), so two exporters in one capture clash only when they share that ID and use the same template IDs with different layouts.

## Simulate a network with nl6

The [nl6](https://github.com/labmonkeys-space/nl6) simulator emits NetFlow v5, NetFlow v9, IPFIX and sFlow from simulated devices and answers SNMP for them.
The [e2e tier](testing.md) runs it through Testcontainers; the container settings and image pin are under [Container images](testing.md#container-images).
