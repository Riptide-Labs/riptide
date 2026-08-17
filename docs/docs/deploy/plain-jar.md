---
sidebar_position: 2
title: Plain JAR
---

# Deploy as a plain JAR

Riptide is a single Spring Boot jar. Requirements: **Java 25** and a reachable ClickHouse.

On Debian/Ubuntu or RHEL-family systems, prefer the
[DEB / RPM packages](linux-packages.md) — same jar, plus a managed systemd service.

Download the jar from the latest [GitHub release](https://github.com/Riptide-Labs/riptide/releases)
and run it:

```bash
curl -LO https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide-flows-%%VERSION%%.jar
java -jar riptide-flows-%%VERSION%%.jar
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
| `riptide.snmp.credentials.monitoring.security-name` | `RIPTIDE_SNMP_CREDENTIALS_MONITORING_SECURITYNAME` |
| `riptide.inventory.file` | `RIPTIDE_INVENTORY_FILE` |

Agent ranges and enrichment entries are the exception: they live in the
[inventory file](../configuration/agent-configuration.md) and cannot be supplied
through environment variables at all.

Environment-variable configuration is fixed for the process lifetime — changing it
means a restart. File-based configuration can
[hot-reload](operations.md#config-hot-reload) instead.

Environment variables suit flat settings and containerized deployments (the compose stack
configures ClickHouse this way). Prefer the config file for anything map-keyed, such as
credential sets and polling profiles: Spring flattens map keys arriving from the
environment (case and dashes are lost), so `RIPTIDE_SNMP_CREDENTIALS_CORPV3_SECURITYNAME`
defines a set named `corpv3` — and an inventory entry saying `credentials: corp-v3` then
fails the load with a dangling reference.

Secret references (`env://`, `file://`, `vault://`, `sops://`) work the same in both —
see [Secret references](../configuration/secret-references.md).
