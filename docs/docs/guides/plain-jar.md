---
title: Plain JAR
description: Download, verify and run the release jar, and how file, environment and command-line configuration reach it.
---

# Run riptide as a plain JAR

Riptide is one Spring Boot jar.
On Debian, Ubuntu or RHEL-family hosts prefer the [DEB and RPM packages](linux-packages.md), which install the same jar with a systemd unit.

## Prerequisites

| Requirement | Value |
| --- | --- |
| Java runtime | 25 |
| ClickHouse | reachable at `riptide.clickhouse.endpoint`, default `http://localhost:8123`; see [startup wait](../reference/clickhouse.md#startup-wait) for what happens while it is not |
| cosign | only for the verification step |

## Steps

1. Download the jar and its signature bundle from the [GitHub release](https://github.com/Riptide-Labs/riptide/releases):

   ```bash
   curl -fsSLO https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide-flows-%%VERSION%%.jar
   curl -fsSLO https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide-flows-%%VERSION%%.jar.sigstore.json
   ls -l riptide-flows-%%VERSION%%.jar*
   ```

   Expected output:

   ```text
   -rw-r--r--  1 you  wheel  61721569 Sep 23 12:51 riptide-flows-%%VERSION%%.jar
   -rw-r--r--  1 you  wheel     10151 Sep 23 12:51 riptide-flows-%%VERSION%%.jar.sigstore.json
   ```

2. Verify the signature. The signing identity is the release workflow; there is no key to fetch.

   ```bash
   cosign verify-blob riptide-flows-%%VERSION%%.jar \
     --bundle riptide-flows-%%VERSION%%.jar.sigstore.json \
     --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com
   ```

   Expected output:

   ```text
   Verified OK
   ```

   The image signature and the build provenance check are in [RELEASING.md](https://github.com/Riptide-Labs/riptide/blob/main/RELEASING.md#verifying-a-release).

3. Write **`/etc/riptide/config.yaml`**. Riptide imports it if it exists; every `riptide.*` setting from the configuration chapters goes there.

   ```yaml
   riptide:
     clickhouse:
       endpoint: http://clickhouse.example.com:8123
       database: riptide
       password: env://CLICKHOUSE_PASSWORD
     receivers:
       ipfix:
         type: ipfix
         host: 0.0.0.0
         port: 4739
   ```

4. Run it:

   ```bash
   export CLICKHOUSE_PASSWORD=...
   java -jar riptide-flows-%%VERSION%%.jar
   ```

   Expected output, among the startup log lines:

   ```text
   org.riptide.RiptideApplication           : Starting RiptideApplication v%%VERSION%% using Java 25.0.4.1 with PID 6126 (...)
   org.riptide.RiptideApplication           : Started RiptideApplication in 1.679 seconds (process running for 2.098)
   ```

   Without any receiver configured the process starts and warns:

   ```text
   org.riptide.flows.Daemon                 : No receivers configured — this daemon will not ingest any flows. Configure riptide.receivers.<name> to receive.
   ```

## Configuration sources

| Source | Form | Reloads without restart |
| --- | --- | --- |
| **`/etc/riptide/config.yaml`** | YAML, imported by the bundled `spring.config.import=optional:file:/etc/riptide/config.yaml` | yes, with [config hot-reload](../operations/hot-reload.md) |
| Environment variables | Spring relaxed binding, see below | no; a process environment is immutable |
| Command line | `--riptide.clickhouse.endpoint=...` after the jar name | no |
| Inventory file named by `riptide.inventory.file` | YAML, read directly | yes |

Agent ranges and, with [dynamic discovery](../reference/discovery.md) off, enrichment entries exist only in the [inventory file](../reference/agent-configuration.md).
They cannot be set through the environment or the command line.

### Environment variables

Uppercase the property, turn dots and dashes into underscores, and write a list index as `_0_`.

| Property | Environment variable |
| --- | --- |
| `riptide.clickhouse.endpoint` | `RIPTIDE_CLICKHOUSE_ENDPOINT` |
| `riptide.receivers.ipfix.port` | `RIPTIDE_RECEIVERS_IPFIX_PORT` |
| `riptide.mcp.auth.tokens[0]` | `RIPTIDE_MCP_AUTH_TOKENS_0_` |
| `riptide.inventory.file` | `RIPTIDE_INVENTORY_FILE` |

Map keys lose their case and dashes on the way in.
`RIPTIDE_SNMP_CREDENTIALS_CORPV3_VERSION=v3` with `RIPTIDE_SNMP_CREDENTIALS_CORPV3_SECURITYNAME=monitoring` defines a credential set named `corpv3`, and an inventory entry saying `credentials: corp-v3` then fails the load:

```text
Inventory file /etc/riptide/inventory.yaml carries problems in 1 entry:
  - Agent range '10.20.30.7' references credential set 'corp-v3' which is not defined.
```

Put map-keyed settings such as credential sets and polling profiles in the configuration file.
Secret references work the same from every source, see [secret references](../reference/secret-references.md).

## Subcommands

The same jar runs the administrative commands.
They exit without starting the daemon.

| Command | Purpose | Documented on |
| --- | --- | --- |
| `java -jar riptide-flows-%%VERSION%%.jar convert <old-config>` | Migrate a 0.8 configuration | [Upgrading from 0.8](../operations/upgrades/upgrading-from-0.8.md) |
| `java -jar riptide-flows-%%VERSION%%.jar onboard ...`, `offboard ...`, `revoke-legacy ...` | Provision a tenant on ClickHouse | [Multi-tenancy](../operations/tenants/onboard-a-tenant.md) |
