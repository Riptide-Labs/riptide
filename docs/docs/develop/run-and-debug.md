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

ClickHouse on `localhost:8123` (which is the application default), plus
[ch-ui](http://localhost:5521) to inspect the `flows` table and
[Grafana](http://localhost:3000).

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

**Replay a capture** — reproduce bugs from a pcap with `contrib/reply-pcap.py`
(needs `pyshark` — which requires `tshark` — plus `click` and `tqdm`):

```bash
python3 contrib/reply-pcap.py capture.pcap    # replays NetFlow/cflow packets to 127.0.0.1:9999
```

**Simulate a network** — the [nl6](https://github.com/labmonkeys-space/nl6) simulator
emits NetFlow v5/v9 and IPFIX from simulated devices; the [e2e tier](testing.md) runs it
automatically, and `src/test/java/org/riptide/e2e/Nl6Container.java` shows how to run it
standalone.

Watch enriched rows land in `riptide.flows` via ch-ui.
