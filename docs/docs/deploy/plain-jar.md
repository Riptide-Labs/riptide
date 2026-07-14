---
sidebar_position: 2
title: Plain JAR
---

# Deploy as a plain JAR

Riptide is a single Spring Boot jar. Requirements: **Java 25** and a reachable ClickHouse.

Download the jar from a [GitHub release](https://github.com/Riptide-Labs/riptide/releases)
and run it:

```bash
java -jar riptide-flows-<version>.jar
```

## Configuration file

Riptide loads an optional external configuration file:

```
/etc/riptide/config.yaml
```

Everything from the [configuration chapters](../configuration/receivers.md) goes there —
receivers, nodes & SNMP, secret references, ClickHouse:

```yaml
riptide:
  clickhouse:
    endpoint: http://clickhouse.example.com:8123
    database: riptide
  receivers:
    ipfix:
      type: ipfix
      host: 0.0.0.0
      port: 4739
```

## Environment variables

Every `riptide.*` property can also be set as an environment variable (Spring relaxed
binding): uppercase, dots and dashes become underscores, list indexes become `_0_`:

| Property | Environment variable |
|---|---|
| `riptide.clickhouse.endpoint` | `RIPTIDE_CLICKHOUSE_ENDPOINT` |
| `riptide.receivers.ipfix.port` | `RIPTIDE_RECEIVERS_IPFIX_PORT` |
| `riptide.nodes.core-router.subnet-address` | `RIPTIDE_NODES_COREROUTER_SUBNETADDRESS` |
| `riptide.nodes.core-router.snmp.community` | `RIPTIDE_NODES_COREROUTER_SNMP_COMMUNITY` |

Environment-variable configuration is fixed for the process lifetime — changing it
means a restart. File-based configuration can
[hot-reload](operations.md#config-hot-reload) instead.

Environment variables suit flat settings and containerized deployments (the compose stack
configures ClickHouse this way). Prefer the config file for nodes — Spring flattens map
keys arriving from the environment (case and dashes are lost).

Secret references (`env://`, `file://`, `vault://`, `sops://`) work the same in both —
see [Secret references](../configuration/secret-references.md).
