---
title: JVM defaults
sidebar_position: 15
description: The JVM flags every riptide launch path sets by default, the container image's AOT cache, where each launch path sets them, and how to turn one off.
---

# JVM defaults reference

## Default flags

| Flag | Container image | deb and rpm | Nix | Turn it off with |
| --- | --- | --- | --- | --- |
| **`-XX:+UseCompactObjectHeaders`** | yes | yes | yes | **`-XX:-UseCompactObjectHeaders`** |
| **`-XX:AOTCache=/app/riptide.aot`** | yes | no | no | **`-XX:AOTMode=off`** |

`-XX:+UseCompactObjectHeaders` is JDK 25's compact object headers (JEP 519).
Object headers shrink from 12 to 8 bytes on a 64-bit JVM.
`-XX:AOTCache` starts the JVM from an ahead-of-time cache (JEP 483, 514 and 515) with classes already loaded and linked and method profiles from a training run.
The image build trains it per architecture by replaying the flow captures in `src/test/resources/flows` through the image's own JVM, see [The container image's AOT cache](#the-container-images-aot-cache).
The plain jar sets nothing by itself.
To run it like the image and the packages, pass the flag on the command line, `java -XX:+UseCompactObjectHeaders -jar riptide-flows-*.jar`, as the [plain JAR guide](../guides/plain-jar.md) does.

## Where each launch path sets them

| Launch path | Set in | Operator channel | Why the operator's flag wins |
| --- | --- | --- | --- |
| Container image | **`JAVA_TOOL_OPTIONS`** in the image | **`JDK_JAVA_OPTIONS`** | The JVM reads `JAVA_TOOL_OPTIONS` before the command line, and the launcher puts `JDK_JAVA_OPTIONS` on the command line. For a repeated `-XX` flag the last one wins. |
| deb and rpm | **`JAVA_TOOL_OPTIONS`**, set by `Environment=` in `riptide.service` | **`JAVA_OPTS`** or **`JDK_JAVA_OPTIONS`** in `/etc/riptide/riptide.env` | Same as the container image. |
| Nix | **`JAVA_TOOL_OPTIONS`**, prefixed by the `riptide` launcher | **`JDK_JAVA_OPTIONS`** in `services.riptide.environmentFile` | Same as the container image. |

Overriding the image's `CMD` keeps the defaults, because they are not part of `CMD`.

> **Warning:** Setting **`JAVA_TOOL_OPTIONS`** in the container or in `riptide.env` replaces the default value, and with it every default on this page. Put your own flags in **`JDK_JAVA_OPTIONS`** (or `JAVA_OPTS` on a package install) instead. On Nix, your `JAVA_TOOL_OPTIONS` is appended to the default, so the default stays.

## The container image's AOT cache

| Fact | Value |
| --- | --- |
| File | **`/app/riptide.aot`**, about 80 MB |
| Layout | The image runs the extracted jar: **`/app/riptide.jar`** is the thin jar, its manifest names **`/app/lib/*.jar`**. A replaced `CMD` that names `-jar /app/riptide.jar` still works. |
| Valid for | The image's own JDK, class path and `-XX:+UseCompactObjectHeaders`. |
| Set in | The image's **`ENTRYPOINT`**, `java -XX:AOTCache=/app/riptide.aot`, so a `docker exec` of `jcmd` or `jfr` does not try to use it. Replacing the entrypoint drops the cache. |
| Settings that make the JVM skip it | `-XX:-UseCompactObjectHeaders`; `-XX:+UseZGC`; a maximum heap of about 30 GiB or more, by `-Xmx` or by default when a container with no memory limit runs on a host with about 120 GB of RAM or more (the JVM then turns compressed object pointers off); any change to the class path. Measured: `-Xmx8g`, `-Xmx512m` and `-XX:+UseParallelGC` keep it. |
| What a skipped cache looks like | One line at start, `[warning][aot] Unable to use AOT cache.`, followed by the reason; riptide then starts without the cache. |
| Turn it off | **`JDK_JAVA_OPTIONS=-XX:AOTMode=off`** |

Check that the image uses the cache. No ClickHouse is needed: riptide stops once the line is printed.

```bash
docker run --rm -e JDK_JAVA_OPTIONS=-Xlog:aot ghcr.io/riptide-labs/riptide:%%VERSION%% \
  -jar /app/riptide.jar --riptide.clickhouse.startup-wait=0s 2>&1 | grep -m1 'Using AOT-linked classes'
```

Expected output:

```text
[0.015s][info][aot] Using AOT-linked classes: true (static archive: has aot-linked classes)
```

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

## Open questions

- Measured effect of the AOT cache on start-up and early drops: not yet measured on the benchmark lab (#987).
