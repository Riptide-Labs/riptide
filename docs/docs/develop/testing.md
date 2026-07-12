---
sidebar_position: 3
title: Testing
---

# Testing

Three tiers, separated so the fast loop stays fast:

| Tier | Command | Needs | What runs |
|---|---|---|---|
| Unit | `mvn test` (or `make`) | JDK | 550+ tests, no containers |
| Integration/e2e | `make e2e` | Docker | `*IT` classes: real ClickHouse, real Vault, and nl6-driven NetFlow v5/v9 + IPFIX through the full pipeline with ledger reconciliation |
| Full mode | see below | Linux | per-device exporter IPs + SNMP enrichment walk-back to simulated agents |

## Full mode (Linux only)

nl6 devices export from their own source IPs and Riptide's SNMP enrichment walks back to
each device's simulated agent:

```bash
docker network create --subnet 172.30.42.0/24 nl6-fullmode
sudo ip route add 10.42.0.0/16 via 172.30.42.10
RIPTIDE_E2E_FULL_MODE=1 make e2e
```

Skipped automatically elsewhere (e.g. macOS) — CI runs it on every PR.

## Coverage

```bash
make coverage    # report: target/site/jacoco/index.html
```

The build enforces a floor (65% instruction / 55% branch); see
[Pull requests](pull-requests.md) for all gates.

The nl6 image tag is pinned in `Nl6Container.java` and bumped deliberately — it is a
wire-format contract, not a Dependabot-managed dependency.
