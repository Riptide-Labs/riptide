---
title: DEB / RPM packages
description: Install the release package on Debian, Ubuntu, RHEL-family or Fedora, what it puts where, and how the systemd service is configured and upgraded.
---

# Install the DEB or RPM package

Every [GitHub release](https://github.com/Riptide-Labs/riptide/releases) ships one architecture-independent `.deb` and one `.rpm`.
Each installs the jar, a sandboxed systemd unit and a configuration skeleton, and pulls a Java 25 runtime from your distribution's repositories.

## Prerequisites

| Distribution | Java 25 package the dependency resolves to |
| --- | --- |
| Debian 13 or later, Ubuntu 22.04 or later | `openjdk-25-jre-headless`, or an installed Temurin, Corretto or Zulu 25 |
| RHEL, Rocky, Alma 9.7 or later and 10.1 or later, Fedora 43 or later | `java-25-openjdk-headless` from AppStream, or an installed Temurin or Zulu 25 |

Amazon Corretto's rpm does not provide the `jre-25-headless` virtual the rpm depends on.
With Corretto as the runtime, install with `rpm -i --nodeps` and manage the Java requirement yourself, or let dnf install `java-25-openjdk-headless` alongside.

## Steps

1. Install the package.

   Debian or Ubuntu:

   ```bash
   curl -fsSLO https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide_%%VERSION%%_all.deb
   sudo apt install ./riptide_%%VERSION%%_all.deb
   ```

   Expected output, in apt's run:

   ```text
   Riptide is installed but not enabled or started.
   Configure /etc/riptide/config.yaml, then run: systemctl enable --now riptide
   ```

   RHEL family or Fedora:

   ```bash
   sudo dnf install https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide-%%VERSION%%-1.noarch.rpm
   ```

   Expected output, in dnf's installed list:

   ```text
     java-25-openjdk-headless-1:25.0.3.0.9-1.el9_7.aarch64
     riptide-%%VERSION%%-1.noarch
   ```

2. Verify what landed:

   ```bash
   dpkg -L riptide | grep -v '^/usr/share/doc\|^/\.$' | sort
   stat -c '%U:%G %a %n' /etc/riptide/config.yaml /etc/riptide/riptide.env
   ```

   Expected output:

   ```text
   /etc
   /etc/riptide
   /etc/riptide/config.yaml
   /etc/riptide/riptide.env
   /usr
   /usr/lib
   /usr/lib/systemd
   /usr/lib/systemd/system
   /usr/lib/systemd/system/riptide.service
   /usr/share
   /usr/share/riptide
   /usr/share/riptide/riptide.jar
   root:riptide 640 /etc/riptide/config.yaml
   root:riptide 640 /etc/riptide/riptide.env
   ```

   On the RPM side `rpm -ql riptide` lists the four files and `/usr/share/doc/riptide/copyright`, without directory entries.

3. Edit **`/etc/riptide/config.yaml`**. Point it at ClickHouse over the HTTP interface and define at least one receiver. Nothing starts until you do.

   ```yaml
   riptide:
     clickhouse:
       endpoint: http://clickhouse.example.com:8123
       database: riptide
       username: riptide
       password: vault://secret/riptide/clickhouse#password
     receivers:
       ipfix:
         type: ipfix
         host: 0.0.0.0
         port: 4739
   ```

   The password is a [secret reference](../reference/secret-references.md), so no plaintext lives in the file.
   Leave it unset for the `default` user's empty password.
   A ClickHouse credential that cannot be resolved fails startup.
   With `manage-schema: true`, the default, riptide creates the database and the `flows` table on first start; the user needs `CREATE`. See [ClickHouse](../reference/clickhouse.md).

4. Enable and start the service, then follow the log:

   ```bash
   sudo systemctl enable --now riptide
   journalctl -u riptide -f
   ```

   The journal carries these two lines among the first, each behind journald's timestamp, host and `java[PID]:` prefix, with the Java version your distribution installed:

   ```text
   org.riptide.RiptideApplication           : Starting RiptideApplication v%%VERSION%% using Java 25.0.4.1 with PID 6126 (...)
   org.riptide.RiptideApplication           : Started RiptideApplication in 1.679 seconds (process running for 2.098)
   ```

## What the package installs

| Path | Owner and mode | Purpose |
| --- | --- | --- |
| **`/usr/share/riptide/riptide.jar`** | root, 0644 | The engine. Also the CLI for `convert`, `onboard`, `offboard` and `revoke-legacy`, see [Plain JAR](plain-jar.md#subcommands). |
| **`/usr/lib/systemd/system/riptide.service`** | root, 0644 | The unit, see below. |
| **`/etc/riptide/config.yaml`** | `root:riptide`, 0640 | Configuration. May hold credentials. Never overwritten on upgrade. |
| **`/etc/riptide/riptide.env`** | `root:riptide`, 0640 | `EnvironmentFile` of the unit: `JAVA_OPTS` and `RIPTIDE_*` variables. Never overwritten on upgrade. |
| **`/usr/share/riptide/grafana/dashboards/`** | root, 0644 | The nine Grafana dashboards and their provisioning file, replaced on upgrade. How to point a Grafana at them is on the [Grafana dashboards](grafana-dashboards.md) page. |

The pre-install script creates the `riptide` system user and group with no home and `nologin`.
The post-install script only reloads systemd; it does not enable or start anything.
Removing the package stops and disables the unit.

## The systemd unit

| Directive | Value | Effect |
| --- | --- | --- |
| `User`, `Group` | `riptide` | Runs unprivileged. |
| `EnvironmentFile` | `-/etc/riptide/riptide.env` | Optional; read at every start. |
| `ExecStart` | `/usr/bin/java $JAVA_OPTS -jar /usr/share/riptide/riptide.jar` | |
| `Restart` | `on-failure` | |
| `SuccessExitStatus` | `143` | The JVM exits 143 on SIGTERM after its shutdown hooks; systemd counts that as a clean stop. |
| `NoNewPrivileges`, `PrivateTmp`, `ProtectHome` | `yes` | |
| `ProtectSystem` | `strict` | The whole filesystem is read-only to the service. Reads still work outside the paths `ProtectHome` and `PrivateTmp` hide, so a `file://` secret in `/run/secrets` or an inventory file under `/etc` or `/var/lib` needs no drop-in; one under `/root`, `/home` or `/tmp` is not reachable. |
| `ReadOnlyPaths` | `/etc/riptide` | The service can read but not rewrite its own configuration. |
| `After`, `Wants` | `network-online.target` | |

`riptide.env` takes `JAVA_OPTS`, such as `-Xmx2g`, and any `riptide.*` setting as an environment variable, see [Plain JAR](plain-jar.md#environment-variables).
`JAVA_OPTS` is one assignment and the last line wins, so keep every option on one line.
A change in `riptide.env` needs `systemctl restart riptide`.
A change in `config.yaml` is picked up by [config hot-reload](hot-reload.md) when it is enabled.

### Bind a port below 1024

The conventional receiver ports, IPFIX 4739, NetFlow v9 2055 and sFlow 6343, need no privilege.
For a port below 1024 add a drop-in:

```bash
sudo systemctl edit riptide
```

```ini
[Service]
AmbientCapabilities=CAP_NET_BIND_SERVICE
```

## Upgrade

Install the newer package the same way.
The running JVM keeps executing the old jar until you restart:

```bash
sudo systemctl restart riptide
```

Your edited `/etc/riptide` files are kept.
rpm writes a changed packaged default next to yours as `.rpmnew`.
dpkg keeps your version unless the packaged default also changed, in which case it asks; unattended upgrades pass `-o Dpkg::Options::=--force-confold` to keep yours.

## Open questions

- The `systemctl enable --now` and `journalctl` steps were not run on a systemd host in this revision. The package contents, permissions, user and Java resolution were verified by installing the v%%VERSION%% packages in Debian trixie and Rocky Linux 9 containers; the log lines come from running the jar directly.
