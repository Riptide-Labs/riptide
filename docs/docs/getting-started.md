---
sidebar_position: 2
title: Getting started
---

# Getting started

## Requirements

- git
- Java 25
- Docker with Docker Compose (for the container image and the e2e test tier)

## Build from source

```bash
git clone https://github.com/Riptide-Labs/riptide.git && cd riptide
```

Compile with tests and produce a runnable jar in `target/`:

```bash
make
```

Build a container image into your local registry:

```bash
make oci
```

## Container images

Published images live at `ghcr.io/riptide-labs/riptide` (linux/amd64 + linux/arm64):

| Tag | Meaning |
|---|---|
| `:<version>`, `:latest` | releases (`v*` tags) |
| `:rc` | floating — rebuilt from every merge to `main` |

```bash
docker pull ghcr.io/riptide-labs/riptide:rc
```

Render a test coverage report (JaCoCo):

```bash
make coverage
```

## Integration and e2e tests

The e2e tier drives real NetFlow v5/v9 and IPFIX traffic from the
[nl6](https://github.com/labmonkeys-space/nl6) simulator through Riptide into ClickHouse
(requires Docker):

```bash
make e2e
```

An optional **full mode** (Linux only) lets nl6 devices export flows from per-device
source IPs while Riptide's SNMP enrichment walks back to each device's simulated agent:

```bash
docker network create --subnet 172.30.42.0/24 nl6-fullmode
sudo ip route add 10.42.0.0/16 via 172.30.42.10
RIPTIDE_E2E_FULL_MODE=1 make e2e
```

## Configuration

Riptide is a Spring Boot application. Besides `application.properties`, it loads an
optional external configuration file:

```properties
spring.config.additional-location=optional:file:/etc/riptide/config.yaml
```

The configuration chapters describe [receivers](configuration/receivers.md),
[nodes & SNMP](configuration/nodes-and-snmp.md),
[secret references](configuration/secret-references.md), and
[ClickHouse](configuration/clickhouse.md). By default no receivers are configured —
the daemon starts no listeners until you define them.
