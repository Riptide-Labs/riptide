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

## Measured effect of compact object headers

Benchmark lab, 2026-10-04: the same build of riptide with `-XX:-UseCompactObjectHeaders` (A) and with the default (B), 4 A holds and 3 B holds of 1,200 s each, alternated A B B A A B A, every hold at 11,000 exporters and 68,499 flows/s with no drops, `-Xmx5g`, G1 (the JVM's ergonomic choice on the 4-vCPU, 7 GiB host), Debian OpenJDK 25.0.4.1.

| Metric | A, flag off: median (min to max) | B, flag on: median (min to max) | B against A |
| --- | --- | --- | --- |
| GC time share | 2.82 % (2.61 to 3.10) | 2.46 % (2.46 to 2.57) | −12.6 % |
| Heap in use, mean | 0.830 GiB (0.811 to 0.888) | 0.742 GiB (0.729 to 0.825) | −10.6 % |
| Heap in use, peak | 1.347 GiB (1.299 to 1.385) | 1.249 GiB (1.199 to 1.352) | −7.3 % |
| Heap in use, lowest | 0.285 GiB (0.253 to 0.369) | 0.255 GiB (0.255 to 0.339) | no measurable difference |
| riptide CPU | 2.257 cores (2.227 to 2.276) | 2.333 cores (2.196 to 2.382) | +3.4 % |

A difference counts as measured only when B's median falls outside A's range.
So at this load the flag trades about 3 % more CPU for about 11 % less heap and 13 % less GC time.
The lab exports no heap-after-GC series, so the lowest heap in use over each hold stands in for the live set.
It moved by less than the run-to-run spread.
Three B holds are few.
The CPU and peak-heap ranges of A and B overlap, so treat those two figures as the weakest.

