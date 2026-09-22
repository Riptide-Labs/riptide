---
sidebar_position: 2
title: Run & debug
---

# Run & debug locally

The debugging loop: ClickHouse in a container, Riptide in your IDE, real flow packets on
UDP.

## 1. Start the backing services

```bash
docker compose -f deployment/clickhouse/compose.yml up -d
```

ClickHouse on `localhost:8123` (the application default endpoint), plus
[Grafana](http://localhost:3000), logging in with `admin`/`admin`. Its Explore view
queries the provisioned ClickHouse datasource, so `SELECT * FROM flows` there needs no
setup if you want to inspect the table directly. The stack's `default` user needs a password
(`riptide` unless you set `CLICKHOUSE_PASSWORD`), and the application default is none, so
pass it to Riptide, or `env://CLICKHOUSE_PASSWORD` if the variable is set in the run
configuration:

```
--riptide.clickhouse.password=riptide
```

## 2. Run Riptide under the debugger

Start `org.riptide.RiptideApplication` from your IDE. No receivers are configured by
default — enable one via program arguments or environment:

```
--riptide.receivers.nf9.type=netflow9
--riptide.receivers.nf9.host=0.0.0.0
--riptide.receivers.nf9.port=9999
```

Set breakpoints anywhere — parsers (`org.riptide.flows.parser`), enrichers
(`org.riptide.snmp`, `org.riptide.dns`), or the pipeline.

## 3. Send traffic

**Replay a capture** — reproduce bugs from a pcap with `contrib/reply-pcap.py`.
It needs only `python3`: it reads the libpcap file itself and sends every UDP payload
to the collector, whatever port the exporter used in the capture.

```bash
python3 contrib/reply-pcap.py capture.pcap                     # every UDP payload to 127.0.0.1:9999
python3 contrib/reply-pcap.py capture.pcap --port 4739         # a collector on another port
python3 contrib/reply-pcap.py capture.pcap --match-port 2055   # only datagrams the exporter sent to 2055
python3 contrib/reply-pcap.py capture.pcap --delay 0           # no pacing (default 10 ms between sends)
```

It finishes with one line saying how many payloads it sent and what it skipped, by reason.
Only classic pcap is read; convert a pcapng file first with `tcpdump -r in.pcapng -w out.pcap`.

What a replay is not:

- **The exporter is `127.0.0.1`.** The collector sees the replay host, not the capture's source address,
  so node and SNMP enrichment keyed on the real exporter does not apply unless your node inventory
  maps the loopback address.
- **Timestamps are the capture's.** Rows land at the time the flows were recorded, so a dashboard on
  "last 15 minutes" shows nothing. Widen the time window to cover the capture.
- **Templates must come first.** A capture that starts mid-stream replays data records the collector
  drops until the next template refresh in the capture.
- **Several exporters collapse into one session.** Riptide still tells them apart by observation
  domain (IPFIX) or source ID (NetFlow v9), so two exporters in one capture only clash when they share
  that ID and use the same template IDs with different layouts.

**Simulate a network** — the [nl6](https://github.com/labmonkeys-space/nl6) simulator
emits NetFlow v5/v9 and IPFIX from simulated devices; the [e2e tier](testing.md) runs it
automatically, and `src/test/java/org/riptide/e2e/Nl6Container.java` shows how to run it
standalone.

Watch enriched rows land in `riptide.flows` via Grafana's Explore view.
