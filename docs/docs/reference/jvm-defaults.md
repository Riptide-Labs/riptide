---
title: JVM defaults
sidebar_position: 15
description: The JVM flags every riptide launch path sets by default, where each launch path sets them, and how to turn one off.
---

# JVM defaults reference

## Default flags

| Flag | Container image | deb and rpm | Nix | Turn it off with |
| --- | --- | --- | --- | --- |
| **`-XX:+UseCompactObjectHeaders`** | yes | yes | yes | **`-XX:-UseCompactObjectHeaders`** |

`-XX:+UseCompactObjectHeaders` is JDK 25's compact object headers (JEP 519).
Object headers shrink from 12 to 8 bytes on a 64-bit JVM.
The plain jar sets nothing: `java -jar riptide-flows-*.jar` runs with the JDK's own defaults.

## Where each launch path sets them

| Launch path | Set in | Operator channel | Why the operator's flag wins |
| --- | --- | --- | --- |
| Container image | **`JAVA_TOOL_OPTIONS`** in the image | **`JDK_JAVA_OPTIONS`** | The JVM reads `JAVA_TOOL_OPTIONS` before the command line, and the launcher puts `JDK_JAVA_OPTIONS` on the command line. For a repeated `-XX` flag the last one wins. |
| deb and rpm | **`JAVA_TOOL_OPTIONS`**, set by `Environment=` in `riptide.service` | **`JAVA_OPTS`** or **`JDK_JAVA_OPTIONS`** in `/etc/riptide/riptide.env` | Same as the container image. |
| Nix | **`JAVA_TOOL_OPTIONS`**, prefixed by the `riptide` launcher | **`JDK_JAVA_OPTIONS`** in `services.riptide.environmentFile` | Same as the container image. |

Overriding the image's `CMD` keeps the defaults, because they are not part of `CMD`.

> **Warning:** Setting **`JAVA_TOOL_OPTIONS`** in the container or in `riptide.env` replaces the default value, and with it every default on this page. Put your own flags in **`JDK_JAVA_OPTIONS`** (or `JAVA_OPTS` on a package install) instead. On Nix, your `JAVA_TOOL_OPTIONS` is appended to the default, so the default stays.

## Check the flags of a running riptide

```bash
docker compose exec riptide jcmd 1 VM.flags 2>/dev/null | tr ' ' '\n' | grep CompactObjectHeaders
```

Expected output:

```text
-XX:+UseCompactObjectHeaders
```

On a package install, use the service's PID instead of `1`. `jcmd` ships with a JDK, not with a JRE:

```bash
sudo jcmd "$(systemctl show -p MainPID --value riptide)" VM.flags | tr ' ' '\n' | grep CompactObjectHeaders
```

Expected output:

```text
-XX:+UseCompactObjectHeaders
```

Every JVM a launch path starts prints `Picked up JAVA_TOOL_OPTIONS: -XX:+UseCompactObjectHeaders` on stderr: the collector, the CLI subcommands such as `convert`, and on the package install into the journal.
That line is the JVM confirming the default, not a warning.

## Open questions

- Measured effect on riptide's heap and GC: not yet measured on the benchmark lab (#924).
