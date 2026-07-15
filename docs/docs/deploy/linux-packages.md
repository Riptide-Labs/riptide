---
sidebar_position: 3
title: DEB / RPM packages
---

# Deploy with DEB / RPM packages

Every [GitHub release](https://github.com/Riptide-Labs/riptide/releases) ships
architecture-independent `.deb` and `.rpm` packages (one package serves amd64 and arm64). They
install the jar, a hardened systemd service, and a configuration skeleton — Java 25 is resolved
automatically from your distro or JDK-vendor repositories.

## Install

**Debian / Ubuntu** (Debian 13+, Ubuntu 22.04+):

```bash
curl -LO https://github.com/Riptide-Labs/riptide/releases/download/v<version>/riptide_<version>_all.deb
sudo apt install ./riptide_<version>_all.deb
```

apt pulls `openjdk-25-jre-headless` from the distro archive if no Java 25 is present; an already
installed Temurin, Corretto, or Zulu 25 also satisfies the dependency.

**RHEL / Rocky / Alma (9.7+, 10.1+) and Fedora (43+)** — dnf installs directly from the URL:

```bash
sudo dnf install https://github.com/Riptide-Labs/riptide/releases/download/v<version>/riptide-<version>-1.noarch.rpm
```

dnf pulls `java-25-openjdk-headless` from AppStream; Temurin and Zulu 25 also satisfy the
dependency.

:::note Amazon Corretto on RPM systems
Corretto's rpm does not provide the `jre-25-headless` virtual the package depends on. If Corretto
is your runtime, install the package with `rpm -i --nodeps` and manage the Java requirement
yourself — or let dnf install `java-25-openjdk-headless` alongside.
:::

## What the package installs

| Path | Purpose |
|---|---|
| `/usr/share/riptide/riptide.jar` | the engine |
| `/usr/lib/systemd/system/riptide.service` | sandboxed unit, runs as the `riptide` system user |
| `/etc/riptide/config.yaml` | configuration (root:riptide 0640 — may hold credentials) |
| `/etc/riptide/riptide.env` | JVM options and environment variables for the service |

## Configure, enable, start

Installation is deliberately passive: nothing is enabled or started until Riptide can do something
useful. Point `/etc/riptide/config.yaml` at your ClickHouse and define receivers — everything from
the [configuration chapters](../configuration/receivers.md) goes there — then:

```bash
sudo systemctl enable --now riptide
journalctl -u riptide -f
```

File-based configuration [hot-reloads](operations.md#config-hot-reload). `riptide.env` takes
`JAVA_OPTS` and [environment-variable configuration](plain-jar.md#environment-variables); changes
there need a `systemctl restart riptide`.

## Upgrades

Install the newer package the same way, then restart the service — the running JVM keeps
executing the old jar until you do:

```bash
sudo systemctl restart riptide
```

Your edited `/etc/riptide` files are preserved by default: rpm writes new defaults as `.rpmnew`
next to them; dpkg keeps your version unless the packaged default changed too, in which case it
asks (unattended upgrades can pass `-o Dpkg::Options::=--force-confold` to always keep yours).

## Receivers on privileged ports

The unit runs unprivileged; the conventional receiver ports (IPFIX 4739, NetFlow v9 2055,
sFlow 6343) need no special rights. To bind a port below 1024, add a drop-in:

```bash
sudo systemctl edit riptide
```

```ini
[Service]
AmbientCapabilities=CAP_NET_BIND_SERVICE
```
