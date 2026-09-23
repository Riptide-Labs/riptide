---
sidebar_position: 3
title: Testing
description: The test tiers with what each command runs and needs, how to run one integration test, the integration test classes, the full-mode e2e setup, the coverage floors, the fuzz harnesses and the pinned container images.
---

# Testing reference

## Tiers

| Tier | Command | Needs | Runs |
| --- | --- | --- | --- |
| Unit | **`make coverage`** | JDK | Checkstyle, Error Prone, every `*Test` class with the fuzz harnesses replaying their seed inputs as ordinary tests, and the JaCoCo report. Skips SpotBugs and the coverage floor. |
| Build gate | **`make`** | JDK | The unit tier plus SpotBugs and the coverage floor. What CI's `build` job runs. |
| Integration and e2e | **`make e2e`** | Docker | The build gate plus every `*IT` class under Failsafe: ClickHouse and Vault in Testcontainers, and nl6 exporting NetFlow v5, NetFlow v9, IPFIX and sFlow over UDP through the running pipeline into ClickHouse, reconciled against nl6's ledger. |
| Full mode | **`RIPTIDE_E2E_FULL_MODE=1 make e2e`** | Linux, a host route | Adds `Nl6SnmpEnrichmentIT`; see [Run full mode](#run-full-mode). |
| Fuzzing | **`make fuzz`** | JDK | Coverage-guided fuzzing of the parser harnesses; see [Fuzz harnesses](#fuzz-harnesses). |

Expected output of `make coverage`, last lines:

```text
[INFO] --- jacoco:0.8.15:report (default-cli) @ riptide-flows ---
[INFO] Loading execution data file /home/you/riptide/target/jacoco.exec
[INFO] Analyzed bundle 'riptide-flows' with 514 classes
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:36 min
[INFO] Finished at: 2026-09-23T13:44:29+02:00
[INFO] ------------------------------------------------------------------------
Coverage report: target/site/jacoco/index.html
```

Expected output of `make e2e` on a host without full mode, the Failsafe summary and last lines:

```text
[INFO] Running org.riptide.e2e.Nl6SnmpEnrichmentIT
[WARNING] Tests run: 2, Failures: 0, Errors: 0, Skipped: 2, Time elapsed: 0 s -- in org.riptide.e2e.Nl6SnmpEnrichmentIT
...
[INFO] Tests run: 153, Failures: 0, Errors: 0, Skipped: 2
...
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  05:07 min
[INFO] Finished at: 2026-09-23T13:49:39+02:00
[INFO] ------------------------------------------------------------------------
Coverage report (incl. e2e): target/site/jacoco/index.html
```

On macOS six cases of `UdpSocketDropsTest` are skipped in every tier: they read `/proc/net`.

## Run one integration test

```bash
mvn verify -Pe2e -Dit.test=ClickhouseRepositoryIT
```

Expected output, the Failsafe lines:

```text
[INFO] Running org.riptide.repository.clickhouse.ClickhouseRepositoryIT
...
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.22 s -- in org.riptide.repository.clickhouse.ClickhouseRepositoryIT
...
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:54 min
```

Checkstyle, compilation and the unit tests run first; Failsafe then runs only the named class at `integration-test`, and SpotBugs and the coverage floor run after it at `verify`.
`-Dtest=<class>` runs an `*IT` class too, but under Surefire and outside the `e2e` profile's Failsafe lifecycle.

## Integration test classes

| Class | Proves |
| --- | --- |
| `ClickhouseRepositoryIT` | Schema creation on a fresh server, batch insert, and query-back of the persisted values. |
| `ClickhouseStartupWaitIT` | What the real readiness probe treats as an answer and as silence during the [startup wait](../configuration/clickhouse.md#startup-wait). |
| `DeadLetterIT` | Where the rows of a refused insert go. |
| `PoisonBatchProbeIT`, `MultiBlockPoisonProbeIT` | What one rejected row does to the batch around it, and where a partial write is and is not possible. |
| `RollupRepairIT`, `RollupShapeDriftIT` | In-place rollup repair and shape-drift detection through the provisioned writer role. |
| `ReservedValueIT` | What a row aggregated before an appended rollup column reads back. |
| `TimestampTimezoneIT` | Flow timestamps are stored as absolute instants regardless of the collector host's timezone. |
| `CoverageReportingIT` | The MCP coverage report against a real server. |
| `TenantOnboardingIT`, `TenantWriteBarrierIT`, `TenantQueryIsolationIT` | The `onboard` and `offboard` subcommands, the per-tenant write barrier and the per-tenant reader credential, end to end; see [Multi-tenancy](../deploy/multi-tenancy.md). |
| `VaultSecretResolverIT` | `vault://` [secret references](../configuration/secret-references.md) against a real Vault. |
| `Nl6FlowIngestionIT` | nl6 devices exporting NetFlow v5, NetFlow v9, IPFIX and sFlow into the running listeners, through parsing, enrichment and classification, into ClickHouse; row counts reconciled against nl6's per-collector ledger. |
| `Nl6SnmpEnrichmentIT` | Full mode only: devices export from their own addresses and SNMP enrichment walks back to each device's simulated agent. |

## Run full mode

Prerequisites: Linux, Docker, and root for the route and the sysctls.

1. Create the device network and route into it:

   ```bash
   docker network create --subnet 172.30.42.0/24 nl6-fullmode
   sudo ip route add 10.42.0.0/16 via 172.30.42.10
   sudo sysctl -w net.ipv4.conf.all.rp_filter=2
   sudo sysctl -w net.ipv4.conf.default.rp_filter=2
   ```

2. Run the tier with the gate set:

   ```bash
   RIPTIDE_E2E_FULL_MODE=1 make e2e
   ```

Without **`RIPTIDE_E2E_FULL_MODE=1`** the class is skipped, on every platform.
With it set, a missing route or an unreachable agent fails the run rather than skipping.
CI's `e2e` job provisions exactly these four lines and runs with the gate on every pull request.

## Coverage floors

| Counter | Floor | Checked by |
| --- | --- | --- |
| Instruction | 65 % | `jacoco:check` in `make` and `make e2e` |
| Branch | 55 % | the same |

The floors sit below the unit-only baseline, so a shortfall fails the build before it reaches CI.

## Fuzz harnesses

| Harness | Seeds |
| --- | --- |
| `IpfixParserFuzzTest` | `src/test/resources/org/riptide/flows/fuzz/IpfixParserFuzzTestInputs/parse/*.dat` |
| `Netflow5ParserFuzzTest` | `src/test/resources/org/riptide/flows/fuzz/Netflow5ParserFuzzTestInputs/parse/*.dat` |
| `Netflow9ParserFuzzTest` | `src/test/resources/org/riptide/flows/fuzz/Netflow9ParserFuzzTestInputs/parse/*.dat` |
| `SflowParserFuzzTest` | none committed |

The harnesses live in `src/fuzz/java/org/riptide/flows/fuzz/`.
In every build the seeds replay as ordinary JUnit tests, so a crash input from a fuzz run becomes a regression test once it is copied into the harness's `Inputs/<method>` directory, `parse` for every seed committed today.

```bash
make fuzz FUZZ_TIME=120 FUZZ_TARGET=IpfixParserFuzzTest
```

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`FUZZ_TIME`** | seconds | `120` | Fuzzing budget per target. |
| **`FUZZ_TARGET`** | class name pattern | `*FuzzTest` | Which harness to run. |

The corpus persists in `.cifuzz-corpus/`, which is gitignored and cached by CI.
The nightly `fuzz.yml` workflow runs every harness for 180 seconds.

## Container images

| Image | Pinned in | Read by |
| --- | --- | --- |
| ClickHouse | `.github/e2e-images/clickhouse.Dockerfile` | Every ClickHouse-backed `*IT` class, all but `VaultSecretResolverIT`, through `ContainerImages.clickhouse()`; the CI pre-pull step; and the shipped compose stack, which carries the same pin. |
| nl6 | `.github/e2e-images/nl6.Dockerfile` | `Nl6Container` through `ContainerImages.nl6()`, and the CI pre-pull step. `Nl6Container.java` holds the container settings the simulator needs (`NET_ADMIN` and `SYS_ADMIN`, `/dev/net/tun`) and the HTTP calls that create devices. |

Each file is one `FROM` line, pinned by tag and digest.
Dependabot bumps both.
A new nl6 tag is a wire-format contract with the simulator, so read its release notes before merging the bump.

## Open questions

- `make fuzz` and full mode were not run for this page; their output is not shown.
