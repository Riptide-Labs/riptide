---
title: Enable continuous profiling
description: Ship continuous profiles to a Pyroscope server with one riptide setting and Pyroscope's own environment variables, pick the profiler event, add the JDK native-access flag, and fall back to JFR when a profile looks wrong.
---

# Enable continuous profiling

Riptide ships continuous profiles to a [Pyroscope](https://grafana.com/oss/pyroscope/) server.
It is off by default and starting it is one variable.

## Prerequisites

- A reachable Pyroscope server.
- The deployment's environment file: `/etc/riptide/riptide.env` on the deb and rpm, the file named by `services.riptide.environmentFile` on Nix, the container's environment otherwise.

## Steps

1. Turn profiling on and name the server.

   ```bash
   RIPTIDE_PROFILING_ENABLED=true
   PYROSCOPE_SERVER_ADDRESS=http://pyroscope.internal:4040
   PYROSCOPE_APPLICATION_NAME=riptide
   PYROSCOPE_PROFILER_EVENT=itimer          # default; see the event table below
   PYROSCOPE_LABELS=region=eu-west,role=edge
   PYROSCOPE_UPLOAD_INTERVAL=10s
   ```

   | Name | Type | Default | Description |
   | --- | --- | --- | --- |
   | **`riptide.profiling.enabled`** | boolean | `false` | The only profiling key riptide owns. `RIPTIDE_PROFILING_ENABLED=true` in the environment form. |

   Everything else is Pyroscope's own `PYROSCOPE_*` vocabulary, read by the agent rather than restated here, so an option added upstream works without riptide knowing about it.
   See Pyroscope's own documentation for the full list.

   Set **`PYROSCOPE_SERVER_ADDRESS`** even though nothing forces you to.
   Omit it and the agent falls back to `http://localhost:4040`, where profiling starts cleanly, logs `Profiling started`, and uploads every profile into nothing.
   No error is raised at start, because from the agent's point of view it was configured; the upload failures arrive later, on stderr.

   **`PYROSCOPE_AGENT_ENABLED`** is the one variable riptide overrides unconditionally.
   Setting it to `false` will not turn profiling off; `riptide.profiling.enabled` is the switch this project documents.

2. Add the native-access flag, so profiling keeps working on a JDK that enforces it.

   ```properties
   # deb and rpm only, via /etc/riptide/riptide.env
   JAVA_OPTS=--enable-native-access=ALL-UNNAMED

   # container, Nix, plain `java -jar`, and also fine on the deb and rpm
   JDK_JAVA_OPTIONS=--enable-native-access=ALL-UNNAMED
   ```

   Only `JDK_JAVA_OPTIONS` works everywhere, because the `java` launcher itself reads it.
   `JAVA_OPTS` is expanded only by the deb and rpm unit, whose `ExecStart` names it.
   The container has an exec-form `ENTRYPOINT` and no shell, so it never sees it.
   Nix does not see it either: `nix/package.nix` builds the launcher with `makeWrapper ... --add-flags "-jar ..."`, which emits `exec "<java>" -jar <path> "$@"` and references no environment variable, and `nix/module.nix` points `ExecStart` straight at that wrapper.
   On Nix, put `JDK_JAVA_OPTIONS` in the file named by `services.riptide.environmentFile`.

   `JAVA_OPTS` is a single assignment and the last one wins, so carry every option on one line:

   ```properties
   JAVA_OPTS=-Xmx2g --enable-native-access=ALL-UNNAMED
   ```

   New deb and rpm installs ship this pairing commented out in `/etc/riptide/riptide.env`, beside the profiling toggle.
   An upgrade does not: the file is packaged `config|noreplace`, so an already-edited copy is kept and the new block arrives as `.dpkg-dist` or `.rpmnew` for you to diff.

   `ALL-UNNAMED` covers every class on the classpath, not only the agent.
   For a Spring Boot fat jar there is no narrower target, since all of it is unnamed.
   The flag is absent from the default `ExecStart`, `ENTRYPOINT` and Nix wrapper because profiling is opt-in, and putting it there would grant native access to every deployment.

3. Restart the collector.

## Verify

Read the startup log.

```bash
journalctl -u riptide -n 200 | grep -E 'profiling|Profiling|restricted'
```

Expected output, without the flag:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by io.pyroscope.vendor.one.profiler.AsyncProfiler in an unnamed module (jar:nested:/.../riptide-flows-0.15.0.jar/!BOOT-INF/lib/agent-2.9.2.jar!/)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
2026-09-23 14:37:33.742 [INFO] Profiling started
INFO  o.r.profiling.ProfilingConfiguration     : Continuous profiling started: application=riptide event=ITIMER profiler=ASYNC server=http://localhost:4040 labels={tenant=default, organisation=default, zone=default, system=blinky}. The event named here is the one configured; the agent exposes no reading of what the process actually obtained.
```

With the flag, the four `WARNING:` lines disappear and the two `started` lines remain.
Confirm the flag reached the process at all with:

```bash
tr '\0' '\n' < /proc/<pid>/cmdline | grep enable-native-access
```

`Profiling started` is the agent's own token and `Continuous profiling started` is riptide's.
A green `Continuous profiling started` line means the agent started; it does not mean a server received anything.
If the agent fails to start, it reports on standard error rather than through the collector's log, so riptide checks afterwards and logs an ERROR naming the application and event when nothing started.

Profiles carry your deployment identity as labels: `tenant`, `organisation`, `zone` and `system`, from `riptide.identity.*`.
That is what makes profiles filterable when several collectors report to one server, and it is why profiling is started in-process rather than as a `-javaagent`: the agent has no notion of a tenant or a zone.
Riptide merges those labels into whatever `PYROSCOPE_LABELS` set, so yours survive; on a label-name collision riptide's identity wins.

## Choose the profiler event

The default event is **`itimer`**, which measures CPU time through `setitimer(ITIMER_PROF)`.
It needs no `perf_event_open`, so out of the box nothing is refused and nothing falls back.

| `PYROSCOPE_PROFILER_EVENT` | Measures | Needs `perf_event_open`? |
| --- | --- | --- |
| **`itimer`** (default) | CPU time | no |
| **`cpu`** | CPU time, with kernel stacks | yes |
| **`wall`** | wall-clock, including time blocked on IO and locks | no |
| **`alloc`**, **`lock`** | allocation, contention | no |

If you are chasing time spent waiting rather than computing, ask for `wall`.
Blocked-on-IO and lock-wait frames dominate a wall-clock profile and are nearly absent from a CPU one, so neither `itimer` nor `cpu` will show you a stall.

`cpu` was expected to be refused under the shipped unit, and measurement says otherwise.
On a deployment running the shipped unit file (`User=riptide`, `NoNewPrivileges=yes`, `ProtectSystem=strict`, no capabilities) on Ubuntu 24.04 with `perf_event_paranoid=4`, both `itimer` and `cpu` started and produced correctly attributed samples: around 800 samples over 8 seconds with 99.9 % on the intended method.
What that does not establish is which mechanism `cpu` used.
async-profiler can fall back internally without saying so, and the agent's API exposes only the event that was configured, so the startup line names what was requested and says so explicitly.

## Give the service a stable name

If **`PYROSCOPE_APPLICATION_NAME`** is unset, riptide uses `riptide`.
Left to the agent it would generate `javaspy.<random>` afresh on every start, so each restart would appear as a new service nobody can search for.
Set it explicitly if you run more than one collector against one server, or rely on the identity labels to tell them apart.

## Fall back to JFR when a profile looks wrong

The agent's native libraries are glibc-linked with no musl build among them, and the shipped image is Alpine.
That does not stop it: musl ignores symbol versioning, so they load, and the profiler starts on the shipped image for every event tested (`itimer`, `cpu`, `wall`, on amd64).
It also profiles correctly there.
Measured on `eclipse-temurin:25-alpine` amd64: 801 samples over 8 seconds, 99.88 % attributed to the intended method, against 99.75 % on a glibc image doing the same work.
A tight synthetic loop is the easiest case an unwinder ever sees, and riptide's real hot paths are Netty event loops, virtual threads and JIT-compiled code, so a container profile showing frames that cannot be real is still worth suspecting the unwinder for.

JFR is a second profiler in the same jar.
It uses no native library and no `perf_event_open`, so it behaves identically on musl and glibc.
It needs two variables, not one:

```bash
PYROSCOPE_PROFILER_TYPE=JFR
PYROSCOPE_PROFILER_EVENT=cpu     # required: JFR rejects the default itimer
```

Setting only `PYROSCOPE_PROFILER_TYPE=JFR` does not work.
JFR refuses the default `itimer` event and refuses `wall`; it accepts `cpu`, `alloc` and `lock`.
Riptide logs an ERROR and carries on without profiling if you get this wrong.

What JFR costs you is fidelity.
Its sampling is subject to safepoint bias, so samples land where the JVM can conveniently stop rather than exactly where time is spent, and its allocation and lock profiling are weaker than async-profiler's.
For questions like "which method dominates a rebuild" it is adequate.

## What it costs when it is off

The agent is a dependency, so it ships in every artefact whether or not you enable it: about 5.5 MB of jar, of which roughly 2.3 MB is async-profiler's bundled native libraries.
Nothing is loaded, no thread starts and no connection is opened unless the setting is on.

## The two JDK warnings

The restricted-method warning above is profiling-only.
Measured on one deployment (Ubuntu 24.04, `openjdk 25.0.4`, agent 2.9.1): a journal covering four service starts held three restricted-method warnings and three `Profiling started` lines, and the one start without profiling was clean.
Those are aggregate counts rather than a start-by-start pairing.
Two things would falsify the claim: a start with profiling off that still warns, meaning something else loads a native library, or a start with profiling on that does not, which is what a JDK already denying native access would look like.
Applying the flag on that deployment took the count from three to zero with profiling still running and samples still reaching the server.

Through agent 2.9.1 the agent also triggered a second warning, `sun.misc.Unsafe::arrayBaseOffset has been called by io.pyroscope.vendor.com.google.protobuf.UnsafeUtil$MemoryAccessor`.
That is a terminally deprecated method rather than a restricted one, so `--enable-native-access` never silenced it.
It came from a protobuf copy vendored inside the agent: 2.9.1 vendored protobuf 4.33.5, and the 2.9.2 that riptide ships in `pom.xml`'s `pyroscope.version` vendors 4.36.1, which no longer touches that class when it encodes a profile.
The call has not been deleted: force `io.pyroscope.vendor.com.google.protobuf.UnsafeUtil` to initialise under 2.9.2 and it still warns, attributed to `UnsafeUtil` itself, because 4.36.1 probes `arrayBaseOffset` deliberately to detect a JVM running in deny mode.
Measured with 2.9.1 as a control on one JVM (`openjdk 25.0.4`): each version started the agent against a local server that accepted four uploaded profiles, so the encode path ran either way; 2.9.1 emitted the `arrayBaseOffset` warning and 2.9.2 emitted no line mentioning `Unsafe`.
What would falsify it: any `UnsafeUtil` line in the journal of a collector running the shipped agent.
If you pin an older agent, `--sun-misc-unsafe-memory-access=allow` quiets that warning, subject to the same `JAVA_OPTS` and `JDK_JAVA_OPTIONS` distinction, but when the JDK drops the option an unrecognised flag stops the JVM from starting at all.

## Related

- [Management endpoints and ports](../reference/management.md): where the collector's own metrics are.

## Open questions

- The startup lines above come from the 0.15.0 jar on macOS with no Pyroscope server; the agent then logs `[ERROR] Error uploading snapshot: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4040` every upload interval. The `journalctl` and `/proc/<pid>/cmdline` commands were not run on a systemd host.
