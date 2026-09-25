# SNMP Interface Metrics, Phase 1, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Riptide polls IF-MIB counters from flow exporters and from tagged pure-SNMP devices and writes them to a Prometheus remote-write endpoint, proven end to end against VictoriaMetrics.

**Architecture:** The existing `InterfaceSnapshotPoller` keeps its registration map and schedule and gains a second registration source (inventory entries marked `poll: always`), a collection definition that widens the walk to the counter columns, and a `MetricSink` it hands samples to after each walk. One `PrometheusRemoteWriteSink` batches samples in a bounded queue and POSTs hand-encoded remote-write protobuf compressed with pure-Java snappy. Discovery carries a NetBox tag through the target group labels, the renderer and the composed YAML so a tagged device lands in the inventory with `poll: always`.

**Tech Stack:** Java 25, Spring Boot 4.1 (`@ConfigurationPropertiesScan`), snmp4j 3.13.1, Dropwizard metrics, Guava `Queues.drain`, `HttpURLConnection` with `OutboundHttpTrust`, `io.airlift:aircompressor` (pure-Java snappy), `com.google.protobuf:protobuf-java` at test scope only (wire-format decoding in tests), snmp4j-agent and Testcontainers for the IT.

**Spec:** `docs/superpowers/specs/2026-09-26-snmp-metrics-design.md`

## Global Constraints

- Every new Java file starts with the GPL-3.0-or-later header from `CLAUDE.md`, above `package`, one blank line after `*/`. Non-Java files use the same two lines in the language's comment syntax. No header on Markdown, JSON or fixtures.
- `make jar` runs Checkstyle, Error Prone, SpotBugs, JaCoCo and unit tests, but no `*IT` class. `make e2e` runs the ITs and needs Docker. Say which one you ran.
- Checkstyle traps: no star imports, no unused imports, no `TODO:` comments, wrapped lines start with the operator, utility classes get a private constructor, no Guava `Objects`/`Function`/`Lists` imports, no JUnit 4. `final` on parameters and locals by convention.
- Error Prone promotes `ReferenceEquality`, `UnusedMethod`, `PatternMatchingInstanceof`, `CanonicalDuration` and `NotJavadoc` to errors.
- Config keys are kebab-case; validation messages name the full key, e.g. `riptide.metrics.remote-write.batch.max-samples must be > 0 (got -1)`.
- Any new operator-settable key names its consumer and its proving test (spec, Configuration table). This plan lists them per task.
- Metric names in the Dropwizard registry are dotted, camelCase, no `_total`; `PrometheusExposition` renders them.
- No new runtime dependency beyond `io.airlift:aircompressor`. `protobuf-java` is test scope only.
- Commits use Conventional Commits, `git commit -s`, trailers `Assisted-by: ClaudeCode:claude-fable-5-1`, `Signed-off-by`, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session`. Commit signing is on; do not disable it.
- Log assertions go through `org.riptide.testsupport.LogCapture`. HTTP stubs are the JDK `HttpServer` after `HttpServerConfig.ensureApplied()`.
- Spec deviations recorded in this plan: (1) the `maxExporters` refusal for inventory entries happens in the poller's registration sweep, not in the loader, because `InventoryLoader` is pure and has no access to `SnmpPollConfig`; the whole `always` set is refused, none of it polled, and the count is in the ERROR log. (2) `openspec/` is untracked, so the `snmp-interface-polling` requirement rewrite is a local edit with no commit; the class javadoc is the tracked sibling.

## Review Focus

1. **A `poll: always` entry with a prefix address** (`10.20.30.0/24`). A prefix cannot be walked. Expected: the load is refused with a message naming the entry. Pinned in Task 4.
2. **An `always` entry with no covering agent range.** There is no credential to poll with. Expected: a warning naming the entry on each reload, no registration, no crash. Pinned in Task 7.
3. **Counter columns absent on a device** (an ifXTable row where ifHCInOctets is `noSuchInstance`). Expected: that series is skipped for that row, other columns still sampled, no zero emitted. Pinned in Task 1.
4. **Remote-write endpoint answers 400 for one batch.** Expected: that batch is dropped and counted in `failedSamples`, the flusher keeps running and the next batch is sent. Pinned in Task 6.
5. **A device that is both a flow exporter and tagged `always`, then untagged.** Expected: it stays registered while flows arrive and is removed by silence afterwards. Pinned in Task 7.

---

## File structure

| File | Responsibility |
| --- | --- |
| `src/main/java/org/riptide/metrics/Sample.java` | One sample: metric name, labels, value, timestamp |
| `src/main/java/org/riptide/metrics/MetricSink.java` | Interface the poller hands samples to |
| `src/main/java/org/riptide/metrics/NoopMetricSink.java` | Sink when no URL is configured |
| `src/main/java/org/riptide/metrics/MetricsConfig.java` | `riptide.metrics.*` properties and validation |
| `src/main/java/org/riptide/metrics/RemoteWriteEncoder.java` | Samples to remote-write 1.0 protobuf bytes, snappy |
| `src/main/java/org/riptide/metrics/PrometheusRemoteWriteSink.java` | Bounded queue, flusher, POST, retry, meters |
| `src/main/java/org/riptide/configuration/MetricsConfiguration.java` | Bean wiring for the sink |
| `src/main/java/org/riptide/snmp/collect/CollectionDefinition.java` | Table OID, index label, columns |
| `src/main/java/org/riptide/snmp/collect/CollectionDefinitions.java` | Built-in `if-mib-interfaces`, lookup by name |
| `src/main/java/org/riptide/snmp/collect/CollectedTable.java` | Walk result: rows of info strings and numeric values |
| `src/main/java/org/riptide/snmp/collect/SampleMapper.java` | Rows plus labels to samples and `IfInfo` |
| `src/main/java/org/riptide/snmp/SnmpUtils.java` | Gains a definition-driven walk and a shared `Snmp` |
| `src/main/java/org/riptide/snmp/SnmpService.java`, `DefaultSnmpService.java` | Gains `collect(endpoint, definition, budget)` |
| `src/main/java/org/riptide/snmp/SnmpEndpoint.java`, `AgentEndpointFactory.java` | Carry the profile's definitions |
| `src/main/java/org/riptide/inventory/PollingProfile.java` | Gains `collect` |
| `src/main/java/org/riptide/inventory/PollMode.java`, `ExporterEntry.java`, `InventoryLoader.java`, `InventorySnapshot.java`, `ExporterView.java` | The `poll` key |
| `src/main/java/org/riptide/discovery/NetboxDeviceSource.java`, `ExporterRenderer.java`, `RenderedExporters.java`, `ComposedInventoryDocument.java`, `DiscoveryConfig.java`, `DiscoveryConfiguration.java` | Tag to `poll: always` |
| `src/main/java/org/riptide/snmp/InterfaceSnapshotPoller.java` | Registration source, inventory sweep, sample emission |
| `src/test/java/org/riptide/snmp/TestSnmpAgent.java` | Counter64 columns with mutable values |
| `src/test/java/org/riptide/snmp/SnmpMetricsIT.java` | End to end against VictoriaMetrics |
| `.github/e2e-images/victoriametrics.Dockerfile`, `src/test/java/org/riptide/e2e/ContainerImages.java` | Pinned VM image |
| `docs/docs/reference/snmp-metrics.md`, `metrics.md`, `exporter-enrichment.md`, `agent-configuration.md`, `discovery.md`, `docs/docs/architecture/discovery.md`, `docs/docs/guides/discovery-netbox.md` | Docs |

---

### Task 1: Collection definition, samples and the mapper

**Files:**
- Create: `src/main/java/org/riptide/metrics/Sample.java`
- Create: `src/main/java/org/riptide/snmp/collect/CollectionDefinition.java`
- Create: `src/main/java/org/riptide/snmp/collect/CollectionDefinitions.java`
- Create: `src/main/java/org/riptide/snmp/collect/CollectedTable.java`
- Create: `src/main/java/org/riptide/snmp/collect/SampleMapper.java`
- Test: `src/test/java/org/riptide/snmp/collect/SampleMapperTest.java`
- Test: `src/test/java/org/riptide/snmp/collect/CollectionDefinitionsTest.java`

**Interfaces:**
- Consumes: `org.riptide.snmp.IfInfo(String name, String alias, Long highSpeed)`.
- Produces:
  - `record Sample(String name, Map<String, String> labels, double value, long timestampMs)`
  - `record CollectionDefinition(String name, OID table, String indexLabel, List<Column> columns, int maxRowsPerPdu)` with nested `record Column(OID oid, String metric, ColumnType type)` and `enum ColumnType { COUNTER64, GAUGE, INFO }`; methods `OID[] columnOids()`, `Duration walkBudget(Duration interval)`.
  - `CollectionDefinitions.IF_MIB_INTERFACES`, `static Optional<CollectionDefinition> byName(String)`, `static Set<String> names()`.
  - `record CollectedTable(Map<Integer, CollectedRow> rows, boolean walkFailed)` with `record CollectedRow(Map<String, String> info, Map<String, Long> values)`.
  - `SampleMapper.toSamples(CollectionDefinition, Map<String, String> baseLabels, CollectedTable, long timestampMs) -> List<Sample>` and `SampleMapper.toIfInfo(CollectedRow) -> IfInfo`.

- [ ] **Step 1: Write the failing mapper test**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.junit.jupiter.api.Test;
import org.riptide.metrics.Sample;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleMapperTest {

    private static final Map<String, String> BASE = Map.of(
            "tenant", "t1", "organisation", "o1", "zone", "z1",
            "exporter", "edge-01", "exporter_address", "10.0.0.1");

    @Test
    void everyCounterAndGaugeColumnBecomesOneSeriesPerRow() {
        final var row = new CollectedTable.CollectedRow(
                Map.of("ifName", "Gi0/0/3", "ifAlias", "uplink", "ifHighSpeed", "10000"),
                Map.of("ifHCInOctets", 1_000L, "ifOperStatus", 1L));
        final var table = new CollectedTable(Map.of(3, row), false);

        final List<Sample> samples = SampleMapper.toSamples(
                CollectionDefinitions.IF_MIB_INTERFACES, BASE, table, 1_700_000_000_000L);

        assertThat(samples).extracting(Sample::name)
                .containsExactlyInAnyOrder("ifHCInOctets", "ifOperStatus", "riptide_interface_info");
        final Sample octets = samples.stream().filter(s -> s.name().equals("ifHCInOctets")).findFirst().orElseThrow();
        assertThat(octets.value()).isEqualTo(1_000d);
        assertThat(octets.timestampMs()).isEqualTo(1_700_000_000_000L);
        assertThat(octets.labels()).containsEntry("ifIndex", "3").containsEntry("ifName", "Gi0/0/3")
                .containsEntry("tenant", "t1").doesNotContainKey("ifAlias");
    }

    @Test
    void theInfoSeriesCarriesAliasAndSpeedWithValueOne() {
        final var row = new CollectedTable.CollectedRow(
                Map.of("ifName", "Gi0/0/3", "ifAlias", "uplink", "ifHighSpeed", "10000"),
                Map.of("ifHCInOctets", 1L));
        final var samples = SampleMapper.toSamples(CollectionDefinitions.IF_MIB_INTERFACES, BASE,
                new CollectedTable(Map.of(3, row), false), 1L);

        final Sample info = samples.stream().filter(s -> s.name().equals("riptide_interface_info")).findFirst().orElseThrow();
        assertThat(info.value()).isEqualTo(1d);
        assertThat(info.labels()).containsEntry("ifAlias", "uplink").containsEntry("ifHighSpeed", "10000")
                .containsEntry("ifIndex", "3");
    }

    @Test
    void aMissingCounterOnOneRowSkipsThatSeriesAndNothingElse() {
        // Review Focus 3: noSuchInstance on one column must not become a zero
        final var row = new CollectedTable.CollectedRow(Map.of("ifName", "lo0"), Map.of("ifOperStatus", 1L));
        final var samples = SampleMapper.toSamples(CollectionDefinitions.IF_MIB_INTERFACES, BASE,
                new CollectedTable(Map.of(1, row), false), 1L);

        assertThat(samples).extracting(Sample::name).containsExactlyInAnyOrder("ifOperStatus", "riptide_interface_info");
    }

    @Test
    void aRowWithoutIfNameGetsNoIfNameLabelButIsStillSampled() {
        final var row = new CollectedTable.CollectedRow(Map.of(), Map.of("ifHCInOctets", 5L));
        final var samples = SampleMapper.toSamples(CollectionDefinitions.IF_MIB_INTERFACES, BASE,
                new CollectedTable(Map.of(7, row), false), 1L);

        assertThat(samples).extracting(Sample::name).containsExactly("ifHCInOctets");
        assertThat(samples.get(0).labels()).doesNotContainKey("ifName").containsEntry("ifIndex", "7");
    }

    @Test
    void toIfInfoReadsTheThreeEnrichmentColumns() {
        final var row = new CollectedTable.CollectedRow(
                Map.of("ifName", "Gi0/0/3", "ifAlias", "uplink", "ifHighSpeed", "10000"), Map.of());
        assertThat(SampleMapper.toIfInfo(row)).isEqualTo(new org.riptide.snmp.IfInfo("Gi0/0/3", "uplink", 10_000L));
    }
}
```

- [ ] **Step 2: Write the failing definitions test**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionDefinitionsTest {

    @Test
    void theBuiltInDefinitionIsFoundByNameAndNothingElseIs() {
        assertThat(CollectionDefinitions.byName("if-mib-interfaces")).contains(CollectionDefinitions.IF_MIB_INTERFACES);
        assertThat(CollectionDefinitions.byName("ip-mib")).isEmpty();
        assertThat(CollectionDefinitions.names()).containsExactly("if-mib-interfaces");
    }

    @Test
    void ifMibInterfacesHasFourteenValueColumnsAndThreeInfoColumns() {
        final var def = CollectionDefinitions.IF_MIB_INTERFACES;
        assertThat(def.columns().stream().filter(c -> c.type() != CollectionDefinition.ColumnType.INFO)).hasSize(14);
        assertThat(def.columns().stream().filter(c -> c.type() == CollectionDefinition.ColumnType.INFO))
                .extracting(CollectionDefinition.Column::metric).containsExactly("ifName", "ifAlias", "ifHighSpeed");
        assertThat(def.indexLabel()).isEqualTo("ifIndex");
        assertThat(def.columnOids()).hasSize(17);
    }

    @Test
    void walkBudgetIsEightyPercentOfTheInterval() {
        assertThat(CollectionDefinitions.IF_MIB_INTERFACES.walkBudget(Duration.ofSeconds(60)))
                .isEqualTo(Duration.ofSeconds(48));
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `mvn -q test -Dtest='SampleMapperTest,CollectionDefinitionsTest' -DfailIfNoTests=false`
Expected: compilation failure, `package org.riptide.snmp.collect does not exist`.

- [ ] **Step 4: Write `Sample`**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import java.util.Map;

/**
 * One time series sample bound for a remote-write sink. {@code labels} excludes the metric name;
 * the encoder adds {@code __name__}. Labels are copied so a sample is immutable once built.
 */
public record Sample(String name, Map<String, String> labels, double value, long timestampMs) {

    public Sample {
        labels = Map.copyOf(labels);
    }
}
```

- [ ] **Step 5: Write `CollectionDefinition` and `CollectionDefinitions`**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.snmp4j.smi.OID;

import java.time.Duration;
import java.util.List;

/**
 * What one walk reads and how each row becomes samples. A definition loaded from YAML later is
 * the same record from a different loader; only the built-in one exists in this phase.
 *
 * @param maxRowsPerPdu rows per GETBULK response. Seventeen columns at snmp4j's default of ten
 *                      rows is about 170 varbinds per PDU, which exceeds what many agents will
 *                      answer in one datagram; five keeps a PDU near 1.3 KB.
 */
public record CollectionDefinition(String name, OID table, String indexLabel, List<Column> columns,
                                   int maxRowsPerPdu) {

    /** Share of the poll interval a walk may use before it is abandoned. */
    private static final int BUDGET_PERCENT = 80;

    public enum ColumnType { COUNTER64, GAUGE, INFO }

    public record Column(OID oid, String metric, ColumnType type) {
    }

    public CollectionDefinition {
        columns = List.copyOf(columns);
        if (maxRowsPerPdu <= 0) {
            throw new IllegalArgumentException("maxRowsPerPdu must be > 0, but was " + maxRowsPerPdu);
        }
    }

    public OID[] columnOids() {
        return this.columns.stream().map(Column::oid).toArray(OID[]::new);
    }

    public Duration walkBudget(final Duration interval) {
        return interval.multipliedBy(BUDGET_PERCENT).dividedBy(100);
    }
}
```

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.snmp4j.smi.OID;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.COUNTER64;
import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.GAUGE;
import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.INFO;

/** The built-in definitions a polling profile may name in {@code collect}. */
public final class CollectionDefinitions {

    private static final String IFX = "1.3.6.1.2.1.31.1.1.1.";
    private static final String IF = "1.3.6.1.2.1.2.2.1.";

    /** IF-MIB ifXTable counters plus the three enrichment columns, snmp_exporter naming. */
    public static final CollectionDefinition IF_MIB_INTERFACES = new CollectionDefinition(
            "if-mib-interfaces", new OID("1.3.6.1.2.1.31.1.1"), "ifIndex", List.of(
                    col(IFX + "1", "ifName", INFO),
                    col(IFX + "18", "ifAlias", INFO),
                    col(IFX + "15", "ifHighSpeed", INFO),
                    col(IFX + "6", "ifHCInOctets", COUNTER64),
                    col(IFX + "10", "ifHCOutOctets", COUNTER64),
                    col(IFX + "7", "ifHCInUcastPkts", COUNTER64),
                    col(IFX + "11", "ifHCOutUcastPkts", COUNTER64),
                    col(IFX + "8", "ifHCInMulticastPkts", COUNTER64),
                    col(IFX + "12", "ifHCOutMulticastPkts", COUNTER64),
                    col(IFX + "9", "ifHCInBroadcastPkts", COUNTER64),
                    col(IFX + "13", "ifHCOutBroadcastPkts", COUNTER64),
                    col(IF + "14", "ifInErrors", COUNTER64),
                    col(IF + "20", "ifOutErrors", COUNTER64),
                    col(IF + "13", "ifInDiscards", COUNTER64),
                    col(IF + "19", "ifOutDiscards", COUNTER64),
                    col(IF + "8", "ifOperStatus", GAUGE),
                    col(IF + "7", "ifAdminStatus", GAUGE)),
            5);

    private static final Map<String, CollectionDefinition> BY_NAME = Map.of(
            IF_MIB_INTERFACES.name(), IF_MIB_INTERFACES);

    private CollectionDefinitions() {
    }

    public static Optional<CollectionDefinition> byName(final String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static Set<String> names() {
        return new TreeSet<>(BY_NAME.keySet());
    }

    private static CollectionDefinition.Column col(final String oid, final String metric,
                                                   final CollectionDefinition.ColumnType type) {
        return new CollectionDefinition.Column(new OID(oid), metric, type);
    }
}
```

Note: ifInErrors, ifOutErrors, ifInDiscards and ifOutDiscards are Counter32 on the wire. They are typed `COUNTER64` here because the type only says "monotonic counter" to the mapper; the value is read with `toLong()` either way. The ifTable columns come from a different table than ifXTable, so the walk in Task 2 issues two walks (one per table) and joins on ifIndex.

- [ ] **Step 6: Write `CollectedTable` and `SampleMapper`**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import java.util.Map;

/**
 * One exporter's table as a single walk produced it, keyed by ifIndex. {@code walkFailed} has the
 * same meaning as on {@code SnmpService.InterfaceTable}: no usable table, whatever the reason.
 */
public record CollectedTable(Map<Integer, CollectedRow> rows, boolean walkFailed) {

    public CollectedTable {
        rows = Map.copyOf(rows);
    }

    /** {@code info} holds INFO columns by metric name; {@code values} holds the numeric ones. */
    public record CollectedRow(Map<String, String> info, Map<String, Long> values) {
        public CollectedRow {
            info = Map.copyOf(info);
            values = Map.copyOf(values);
        }
    }
}
```

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.riptide.metrics.Sample;
import org.riptide.snmp.IfInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns collected rows into samples, and a row into the enrichment record. */
public final class SampleMapper {

    public static final String INFO_METRIC = "riptide_interface_info";

    private SampleMapper() {
    }

    public static List<Sample> toSamples(final CollectionDefinition definition,
                                         final Map<String, String> baseLabels,
                                         final CollectedTable table,
                                         final long timestampMs) {
        final List<Sample> samples = new ArrayList<>();
        for (final Map.Entry<Integer, CollectedTable.CollectedRow> entry : table.rows().entrySet()) {
            final CollectedTable.CollectedRow row = entry.getValue();
            final Map<String, String> labels = new HashMap<>(baseLabels);
            labels.put(definition.indexLabel(), String.valueOf(entry.getKey()));
            final String ifName = row.info().get("ifName");
            if (ifName != null) {
                labels.put("ifName", ifName);
            }
            for (final CollectionDefinition.Column column : definition.columns()) {
                if (column.type() == CollectionDefinition.ColumnType.INFO) {
                    continue;
                }
                final Long value = row.values().get(column.metric());
                if (value != null) {
                    samples.add(new Sample(column.metric(), labels, value.doubleValue(), timestampMs));
                }
            }
            if (!row.info().isEmpty()) {
                final Map<String, String> infoLabels = new HashMap<>(labels);
                infoLabels.putAll(row.info());
                samples.add(new Sample(INFO_METRIC, infoLabels, 1d, timestampMs));
            }
        }
        return samples;
    }

    public static IfInfo toIfInfo(final CollectedTable.CollectedRow row) {
        final String speed = row.info().get("ifHighSpeed");
        return new IfInfo(row.info().get("ifName"), row.info().get("ifAlias"),
                speed == null ? null : Long.valueOf(speed));
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='SampleMapperTest,CollectionDefinitionsTest' -DfailIfNoTests=false`
Expected: `Tests run: 8, Failures: 0`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/riptide/metrics/Sample.java src/main/java/org/riptide/snmp/collect src/test/java/org/riptide/snmp/collect
git commit -s -m "feat(snmp): collection definition and sample mapper for IF-MIB counters"
```

---

### Task 2: Definition-driven walk on a shared snmp4j session

**Files:**
- Modify: `src/main/java/org/riptide/snmp/SnmpUtils.java` (add `collect`, refactor `walkColumns` to return raw rows)
- Modify: `src/main/java/org/riptide/snmp/SnmpService.java` (add `collect`)
- Modify: `src/main/java/org/riptide/snmp/DefaultSnmpService.java` (shared `Snmp` per version, `collect`, meters)
- Modify: `src/test/java/org/riptide/snmp/TestSnmpAgent.java` (Counter64 columns, mutable values)
- Test: `src/test/java/org/riptide/snmp/SnmpCollectTest.java`

**Interfaces:**
- Consumes: `CollectionDefinition`, `CollectedTable` from Task 1; `SnmpUtils.WalkCollector`, `SnmpUtils.WalkOutcome`, `SnmpVersion.getSnmpBuilder()`, `getTarget(...)`.
- Produces:
  - `SnmpService.collect(SnmpEndpoint endpoint, CollectionDefinition definition, Duration budget) -> CollectedTable`
  - `SnmpUtils.collect(Snmp snmp, Target<?> target, SnmpEndpoint endpoint, CollectionDefinition definition, long deadlineNanos) -> CollectedTable` (package-private)
  - `DefaultSnmpService` holds one `Snmp` per `SnmpVersion`, created lazily, closed in `@PreDestroy close()`.
  - `TestSnmpAgent.setCounter(int ifIndex, int column, long value)` for tests that need a moving counter.

- [ ] **Step 1: Make the test agent serve Counter64 and expose a setter**

In `TestSnmpAgent.createStaticIfXTable()` change every HC column (ifXTable columns 6 to 13) from `SMIConstants.SYNTAX_COUNTER32` to `SMIConstants.SYNTAX_COUNTER64`, and fill those cells with `new Counter64(value)` instead of `new Integer32(value)`. Keep the field `private DefaultMOTable ifXTable;` referencing the registered table and add:

```java
    /** Overwrites one ifXTable cell so a test can make a counter move between walks. */
    public void setCounter(final int ifIndex, final int column, final long value) {
        final MOTableRow row = this.ifXTable.getModel().getRow(new OID(new int[]{ifIndex}));
        ((MOMutableTableRow) row).setValue(column - 1, new Counter64(value));
    }
```

`DefaultMOTable` rows built with `DefaultMOMutableRow2PC` implement `MOMutableTableRow`; if the fixture uses `DefaultMOTableRow`, switch the row factory to `DefaultMOMutableRow2PCFactory` (snmp4j-agent ships both). Column indexes are 1-based in the MIB and 0-based in the row.

- [ ] **Step 2: Write the failing collect test**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.secrets.SecretResolvers;
import org.riptide.snmp.collect.CollectedTable;
import org.riptide.snmp.collect.CollectionDefinitions;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SnmpCollectTest {

    private static final int PORT = 12400;
    private TestSnmpAgent agent;
    private DefaultSnmpService service;

    @BeforeEach
    void start(@TempDir final Path dir) throws Exception {
        this.agent = new TestSnmpAgent("127.0.0.1/" + PORT, dir);
        this.agent.start();
        this.agent.registerIfTable();
        this.agent.registerIfXTable();
        this.service = new DefaultSnmpService(SecretResolvers.defaults(), new com.codahale.metrics.MetricRegistry());
    }

    @AfterEach
    void stop() {
        this.service.close();
        this.agent.stop();
    }

    @Test
    void collectReadsCountersAndInfoForEveryRowAndJoinsBothTables() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c("127.0.0.1", PORT);
        this.agent.setCounter(1, 6, 123_456_789_012L);

        final CollectedTable table = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10));

        assertThat(table.walkFailed()).isFalse();
        assertThat(table.rows()).containsKeys(1, 2);
        final var eth0 = table.rows().get(1);
        assertThat(eth0.values()).containsEntry("ifHCInOctets", 123_456_789_012L).containsKey("ifOperStatus")
                .containsKey("ifInErrors");
        assertThat(eth0.info()).containsEntry("ifName", "eth0-x").containsEntry("ifHighSpeed", "14");
    }

    @Test
    void twoCollectsReuseTheSameSessionAndSeeAMovedCounter() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c("127.0.0.1", PORT);
        this.agent.setCounter(1, 6, 100L);
        final long before = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).rows().get(1).values().get("ifHCInOctets");
        this.agent.setCounter(1, 6, 250L);
        final long after = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).rows().get(1).values().get("ifHCInOctets");

        assertThat(before).isEqualTo(100L);
        assertThat(after).isEqualTo(250L);
        assertThat(this.service.openSessions()).as("one shared session per SNMP version").isEqualTo(1);
    }

    @Test
    void anUnreachablePortIsAFailedTableNotAnException() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c("127.0.0.1", PORT + 1);
        final CollectedTable table = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(3));
        assertThat(table.walkFailed()).isTrue();
        assertThat(table.rows()).isEmpty();
    }
}
```

`SnmpTest.communityV2c(host, port)` exists as a static helper on `SnmpTest`; make it package-visible if it is private.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q test -Dtest=SnmpCollectTest -DfailIfNoTests=false`
Expected: compilation failure, `cannot find symbol: method collect`.

- [ ] **Step 4: Add `collect` to `SnmpService`**

```java
    /**
     * Walks the columns of {@code definition} and returns every row. Unlike
     * {@link #walkInterfaces} there is no ifTable fallback: a device without ifXTable yields a
     * failed table and no series, which is the spec's "no 32-bit fallback".
     */
    CollectedTable collect(SnmpEndpoint snmpEndpoint, CollectionDefinition definition, Duration budget);
```

- [ ] **Step 5: Refactor `SnmpUtils.walkColumns` into a raw-row walk and add `collect`**

Extract the body of `walkColumns` that runs `TableUtils` and iterates `collector.events()` into:

```java
    /** One table walk: index to the columns' variable bindings, in column order. Null cells kept. */
    record RawTable(Map<Integer, VariableBinding[]> rows, WalkOutcome outcome) {
    }

    static RawTable walkRaw(final Snmp snmp, final Target<?> target, final SnmpEndpoint endpoint,
                            final OID[] columns, final int maxRowsPerPdu, final long deadlineNanos) {
        // identical to today's walkColumns up to the event loop, plus:
        //   tableUtils.setMaxNumRowsPerPDU(maxRowsPerPdu);
        //   tableUtils.setMaxNumColumnsPerPDU(columns.length);
        // and the loop stores tableEvent.getColumns() under tableEvent.getIndex().last()
    }
```

Then rewrite the existing `walkColumns` as `walkRaw(...)` followed by the `row.apply(Arrays.asList(bindings))` mapping, so enrichment behaviour is unchanged. Add:

```java
    static CollectedTable collect(final Snmp snmp, final Target<?> target, final SnmpEndpoint endpoint,
                                  final CollectionDefinition definition, final long deadlineNanos) {
        // ifXTable columns and ifTable columns are two tables; walk each and join on ifIndex
        final Map<OID, List<CollectionDefinition.Column>> byTable = new LinkedHashMap<>();
        for (final CollectionDefinition.Column column : definition.columns()) {
            byTable.computeIfAbsent(tableOf(column.oid()), key -> new ArrayList<>()).add(column);
        }
        final Map<Integer, Map<String, String>> info = new TreeMap<>();
        final Map<Integer, Map<String, Long>> values = new TreeMap<>();
        for (final List<CollectionDefinition.Column> columns : byTable.values()) {
            final OID[] oids = columns.stream().map(CollectionDefinition.Column::oid).toArray(OID[]::new);
            final RawTable raw = walkRaw(snmp, target, endpoint, oids, definition.maxRowsPerPdu(), deadlineNanos);
            if (raw.outcome() != WalkOutcome.OK) {
                return new CollectedTable(Map.of(), true);
            }
            for (final Map.Entry<Integer, VariableBinding[]> row : raw.rows().entrySet()) {
                for (int i = 0; i < columns.size(); i++) {
                    final VariableBinding cell = row.getValue()[i];
                    final CollectionDefinition.Column column = columns.get(i);
                    if (column.type() == CollectionDefinition.ColumnType.INFO) {
                        final String text = string(cell);
                        if (text != null) {
                            info.computeIfAbsent(row.getKey(), k -> new HashMap<>()).put(column.metric(), text);
                        }
                    } else {
                        final Long number = number(cell);
                        if (number != null) {
                            values.computeIfAbsent(row.getKey(), k -> new HashMap<>()).put(column.metric(), number);
                        }
                    }
                }
            }
        }
        final Map<Integer, CollectedTable.CollectedRow> rows = new TreeMap<>();
        for (final Integer index : union(info.keySet(), values.keySet())) {
            rows.put(index, new CollectedTable.CollectedRow(
                    info.getOrDefault(index, Map.of()), values.getOrDefault(index, Map.of())));
        }
        return new CollectedTable(rows, false);
    }

    /** The table entry OID is the column OID without its last sub-identifier. */
    private static OID tableOf(final OID column) {
        return new OID(column.getValue(), 0, column.size() - 1);
    }
```

`string(...)` and `number(...)` are the existing private helpers (they return null for exceptions and null values). `number` must handle `Counter64` through `toLong()`, which snmp4j's `Counter64.toLong()` does. `union` is a small private helper returning a `TreeSet` of both key sets. Keep `MAX_TABLE_ROWS` and the budget handling from `walkRaw` unchanged.

- [ ] **Step 6: Give `DefaultSnmpService` a shared session and `collect`**

```java
    private final Map<SnmpVersion, Snmp> sessions = new EnumMap<>(SnmpVersion.class);
    private final Map<SnmpVersion, SnmpBuilder> builders = new EnumMap<>(SnmpVersion.class);
    private final Meter collects;
    private final Timer collectDuration;
    private final Meter collectsFailed;

    /** One session per version, built on first use. Closing the service closes them all. */
    private synchronized Snmp session(final SnmpVersion version) throws IOException {
        Snmp snmp = this.sessions.get(version);
        if (snmp == null) {
            final SnmpBuilder builder = version.getSnmpBuilder();
            snmp = builder.build();
            this.builders.put(version, builder);
            this.sessions.put(version, snmp);
        }
        return snmp;
    }

    @Override
    public CollectedTable collect(final SnmpEndpoint endpoint, final CollectionDefinition definition,
                                  final Duration budget) {
        this.collects.mark();
        final long deadline = System.nanoTime() + budget.toNanos();
        try (var ignored = this.collectDuration.time()) {
            final SnmpVersion version = endpoint.getSnmpDefinition().getSnmpVersion();
            final Snmp snmp = session(version);
            final Target<?> target = version.getTarget(snmp, this.builders.get(version), endpoint, this.secretResolvers);
            final CollectedTable table = SnmpUtils.collect(snmp, target, endpoint, definition, deadline);
            if (table.walkFailed()) {
                this.collectsFailed.mark();
            }
            return table;
        } catch (IOException | IllegalArgumentException e) {
            this.collectsFailed.mark();
            log.warn("SNMP collect against {} failed: {}", endpoint, e.getMessage());
            return new CollectedTable(Map.of(), true);
        }
    }

    /** Test seam. */
    synchronized int openSessions() {
        return this.sessions.size();
    }

    @PreDestroy
    public synchronized void close() {
        for (final Snmp snmp : this.sessions.values()) {
            try {
                snmp.close();
            } catch (final IOException e) {
                log.debug("Closing SNMP session: {}", e.getMessage());
            }
        }
        this.sessions.clear();
    }
```

Meters: `snmp.collects`, `snmp.collectDuration`, `snmp.collects.failed`, registered next to the existing `snmp.walks.*` ones. Leave `walkInterfaces` on its per-walk session for now; Task 10 moves it too. The `SnmpBuilder` is kept because `getTarget` configures the target on the builder, which is why `allowIncrementalConfigAfterBuild()` is set in `SnmpVersion`. `getTarget` for v3 calls `discoverAuthoritativeEngineID` on the shared session, which is safe: snmp4j caches engine IDs per address.

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='SnmpCollectTest,SnmpTest,SnmpEnricherTest,WalkBoundsTest' -DfailIfNoTests=false`
Expected: all pass; the existing walk tests prove the refactor kept enrichment behaviour.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/riptide/snmp src/test/java/org/riptide/snmp
git commit -s -m "feat(snmp): definition-driven collect on a shared snmp4j session"
```

---

### Task 3: The polling profile names its collections

**Files:**
- Modify: `src/main/java/org/riptide/inventory/PollingProfile.java`
- Modify: `src/main/java/org/riptide/snmp/SnmpEndpoint.java`
- Modify: `src/main/java/org/riptide/snmp/AgentEndpointFactory.java`
- Test: `src/test/java/org/riptide/inventory/PollingProfileTest.java`
- Test: `src/test/java/org/riptide/snmp/AgentEndpointFactoryTest.java`

**Interfaces:**
- Consumes: `CollectionDefinitions.byName`, `CollectionDefinitions.names()`.
- Produces:
  - `PollingProfile(Duration refreshInterval, Duration snapshotExpiry, int timeout, int retries, List<String> collect)`; `List<CollectionDefinition> definitions()`; `validate(name)` refuses unknown names and a timeout budget that cannot fit.
  - `SnmpEndpoint.getCollections() -> List<CollectionDefinition>` (empty by default), `withCollections(List<CollectionDefinition>)`.

- [ ] **Step 1: Write the failing profile tests**

Add to `PollingProfileTest`:

```java
    @Test
    void collectDefaultsToEmptyAndResolvesBuiltInNames() {
        final var profile = PollingProfile.builtInDefault();
        assertThat(profile.collect()).isEmpty();
        assertThat(profile.definitions()).isEmpty();

        final var counters = new PollingProfile(Duration.ofSeconds(60), Duration.ofMinutes(30), 500, 1,
                List.of("if-mib-interfaces"));
        assertThat(counters.definitions()).containsExactly(CollectionDefinitions.IF_MIB_INTERFACES);
    }

    @Test
    void anUnknownCollectionNameIsRefusedByValidate() {
        final var profile = new PollingProfile(Duration.ofSeconds(60), Duration.ofMinutes(30), 500, 1,
                List.of("ip-mib"));
        assertThatThrownBy(() -> profile.validate("brisk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.snmp.polling.brisk.collect")
                .hasMessageContaining("'ip-mib'")
                .hasMessageContaining("if-mib-interfaces");
    }

    @Test
    void aTimeoutThatCannotFitTheWalkBudgetIsRefusedWhenCollecting() {
        // 20 s timeout x 2 attempts = 40 s per PDU, budget is 80% of 30 s = 24 s
        final var profile = new PollingProfile(Duration.ofSeconds(30), Duration.ofMinutes(30), 20_000, 1,
                List.of("if-mib-interfaces"));
        assertThatThrownBy(() -> profile.validate("brisk"))
                .hasMessageContaining("timeout")
                .hasMessageContaining("24");
        // without a collection the same timeout is fine: the two-minute enrichment budget applies
        new PollingProfile(Duration.ofSeconds(30), Duration.ofMinutes(30), 20_000, 1, List.of()).validate("brisk");
    }
```

Add to `AgentEndpointFactoryTest`:

```java
    @Test
    void theEndpointCarriesTheProfilesDefinitions() {
        final var profile = new PollingProfile(Duration.ofSeconds(60), Duration.ofMinutes(30), 500, 1,
                List.of("if-mib-interfaces"));
        final var entry = new AgentEntry("10.0.0.0/24", TestCredentials.communityV2c(), profile, true, 161);
        final SnmpEndpoint endpoint = AgentEndpointFactory.endpointFor(entry, new IPAddressString("10.0.0.7")).orElseThrow();
        assertThat(endpoint.getCollections()).containsExactly(CollectionDefinitions.IF_MIB_INTERFACES);
    }
```

Use whatever credential helper `TestCredentials` already offers; the name above is illustrative of the existing helper's shape, read the file.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest='PollingProfileTest,AgentEndpointFactoryTest' -DfailIfNoTests=false`
Expected: compilation failure on the five-argument constructor.

- [ ] **Step 3: Extend `PollingProfile`**

```java
public record PollingProfile(@DefaultValue(DEFAULT_REFRESH_INTERVAL) Duration refreshInterval,
                             @DefaultValue(DEFAULT_SNAPSHOT_EXPIRY) Duration snapshotExpiry,
                             @DefaultValue("" + DEFAULT_TIMEOUT_MS) int timeout,
                             @DefaultValue("" + DEFAULT_RETRIES) int retries,
                             @DefaultValue List<String> collect) {

    public PollingProfile {
        collect = collect == null ? List.of() : List.copyOf(collect);
    }

    public List<CollectionDefinition> definitions() {
        return this.collect.stream().map(CollectionDefinitions::byName).flatMap(Optional::stream).toList();
    }
```

`@DefaultValue` with no value binds an empty list. Update `builtInDefault()` to pass `List.of()`. In `validate(name)` add, after the existing checks:

```java
        for (final String collection : this.collect) {
            if (CollectionDefinitions.byName(collection).isEmpty()) {
                throw new IllegalStateException(
                        "riptide.snmp.polling.%s.collect names an unknown collection '%s'; known collections are %s."
                                .formatted(name, collection, CollectionDefinitions.names()));
            }
        }
        if (!this.collect.isEmpty()) {
            final long perPduMs = (long) this.timeout * (this.retries + 1);
            final long budgetMs = this.refreshInterval.multipliedBy(80).dividedBy(100).toMillis();
            if (perPduMs > budgetMs) {
                throw new IllegalStateException(
                        "riptide.snmp.polling.%s: timeout %d ms x %d attempts exceeds the walk budget of %d ms (80%% of refresh-interval); lower the timeout or lengthen refresh-interval."
                                .formatted(name, this.timeout, this.retries + 1, budgetMs));
            }
        }
```

Every existing four-argument constructor call in main and test code gets `List.of()` appended. `PollingDefaultsGuardTest` compares defaults across three classes; extend it only if it enumerates record components.

- [ ] **Step 4: Extend `SnmpEndpoint` and `AgentEndpointFactory`**

Add a `private final List<CollectionDefinition> collections;` (never null, `List.of()` in the package-private constructor), a `withCollections(List<CollectionDefinition>)` that returns a copy, and include it in the private all-args constructor. `equals`/`hashCode` are Lombok-generated and include it, which is what re-resolution needs: a profile change that adds a collection must count as an endpoint change. In `AgentEndpointFactory.endpointFor`, after `.withCadence(...)`, add `.withCollections(entry.polling().definitions())`.

- [ ] **Step 5: Run the tests**

Run: `mvn -q test -Dtest='PollingProfileTest,AgentEndpointFactoryTest,PollingDefaultsGuardTest,InventoryLoaderTest' -DfailIfNoTests=false`
Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/riptide/inventory/PollingProfile.java src/main/java/org/riptide/snmp/SnmpEndpoint.java src/main/java/org/riptide/snmp/AgentEndpointFactory.java src/test
git commit -s -m "feat(inventory): polling profiles name the collections to poll"
```

---

### Task 4: The `poll` key on exporter entries

**Files:**
- Create: `src/main/java/org/riptide/inventory/PollMode.java`
- Modify: `src/main/java/org/riptide/inventory/ExporterEntry.java`
- Modify: `src/main/java/org/riptide/inventory/InventoryLoader.java:43-49` (keys), `:537-570` (validateExporters), `:176-180` (snapshot construction)
- Modify: `src/main/java/org/riptide/inventory/InventorySnapshot.java:29-40`
- Modify: `src/main/java/org/riptide/inventory/ExporterView.java`
- Modify: `docs/docs/reference/exporter-enrichment.md:21-52,135`
- Test: `src/test/java/org/riptide/inventory/InventoryLoaderTest.java`

**Interfaces:**
- Produces:
  - `enum PollMode { ON_FLOW, ALWAYS }` with `static PollMode parse(String entryName, Object value)`.
  - `ExporterEntry(String name, IPAddressString address, Long observationDomain, Map<Integer, InterfacePin> interfaces, PollMode poll)`.
  - `ExporterView.alwaysPolled() -> List<ExporterEntry>`, sorted by name, only `ALWAYS` entries.

- [ ] **Step 1: Write the failing loader tests**

```java
    @Test
    void pollDefaultsToOnFlowAndAlwaysIsListedOnTheView() {
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core:
                      address: 10.20.30.7
                    silent-switch:
                      address: 10.20.30.8
                      poll: always
                """, "test.yaml");

        assertThat(snapshot.exporterView().match(netflow("10.20.30.7", 0)).orElseThrow().poll())
                .isEqualTo(PollMode.ON_FLOW);
        assertThat(snapshot.exporterView().alwaysPolled()).extracting(ExporterEntry::name)
                .containsExactly("silent-switch");
    }

    @Test
    void pollAlwaysOnAPrefixIsRefusedBecauseAPrefixCannotBeWalked() {
        // Review Focus 1
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    site:
                      address: 10.20.30.0/24
                      poll: always
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.yaml")
                .hasMessageContaining("'site'")
                .hasMessageContaining("poll: always")
                .hasMessageContaining("host address");
    }

    @Test
    void anUnknownPollValueNamesTheTwoAllowedOnes() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    x:
                      address: 10.20.30.7
                      poll: sometimes
                """, "test.yaml"))
                .hasMessageContaining("'sometimes'")
                .hasMessageContaining("on-flow")
                .hasMessageContaining("always");
    }

    @Test
    void theKnownKeysListInTheUnknownKeyMessageNowIncludesPoll() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    typo:
                      adress: 10.20.30.7
                """, "test.yaml"))
                .hasMessageContaining("[address, interfaces, observation-domain, poll]");
    }
```

The last test replaces the assertion in the existing collected-problems test near line 1195 that quotes the old list.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=InventoryLoaderTest -DfailIfNoTests=false`
Expected: compilation failure on `PollMode` / `alwaysPolled`.

- [ ] **Step 3: Write `PollMode` and extend `ExporterEntry`**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/** When an exporter entry is polled: on its first flow, or from the moment the inventory loads. */
public enum PollMode {
    ON_FLOW("on-flow"),
    ALWAYS("always");

    private final String key;

    PollMode(final String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    static PollMode parse(final String entryName, final Object value) {
        if (value == null) {
            return ON_FLOW;
        }
        final String text = String.valueOf(value);
        for (final PollMode mode : values()) {
            if (mode.key.equals(text)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Exporter '%s' has an unknown poll value '%s'; write on-flow or always.".formatted(entryName, text));
    }
}
```

`ExporterEntry` gains a fifth component `PollMode poll`, defaulted to `ON_FLOW` in the compact constructor when null. Every existing four-argument construction in main and test code gets `PollMode.ON_FLOW` appended (grep `new ExporterEntry(`).

- [ ] **Step 4: Parse and validate in `InventoryLoader`**

Change `EXPORTER_KEYS` to `Set.of("address", "observation-domain", "interfaces", "poll")`. In `validateExporters`, after `parsedAddress` is built:

```java
                final PollMode poll = PollMode.parse(entry.getKey(), entryBody.get("poll"));
                if (poll == PollMode.ALWAYS && parsedAddress.isPrefixed()) {
                    throw new IllegalStateException(
                            "Exporter '%s' has poll: always on the prefix %s; polling needs a host address, one entry per device."
                                    .formatted(entry.getKey(), parsedAddress));
                }
```

and pass `poll` to the `ExporterEntry` constructor. Collect the `ALWAYS` candidates into a list alongside `exporterCandidates` and pass it to the `InventorySnapshot` constructor at line 178.

- [ ] **Step 5: Extend `InventorySnapshot` and `ExporterView`**

Add `private final List<ExporterEntry> alwaysPolled;` to `InventorySnapshot`, a new constructor parameter after `exporters` (the two-argument constructor passes `List.of()`; `empty()` too), and in the anonymous or inner `ExporterView` implementation:

```java
    @Override
    public List<ExporterEntry> alwaysPolled() {
        return this.alwaysPolled;
    }
```

with the list sorted by name and wrapped in `List.copyOf`. `ExporterView` gains `List<ExporterEntry> alwaysPolled();`.

- [ ] **Step 6: Update the reference page**

In `docs/docs/reference/exporter-enrichment.md` add `poll: always` to the YAML example at line 21-36 with a comment, add a row to the key table at lines 44-51:

```markdown
| **`poll`** | `on-flow` or `always` | `on-flow` | `always` registers the device for SNMP polling when the inventory loads, without waiting for a flow. Needs a host address and a covering agent range. |
```

and change the known-keys list in the messages row at line 135 to `[address, interfaces, observation-domain, poll]`. Add a messages row for the prefix refusal and one for the unknown value, quoting the messages from Step 3 and 4 verbatim.

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='InventoryLoaderTest,PinnedPrefixMatcherTest,InventoryPublicationGuardTest' -DfailIfNoTests=false`
Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/riptide/inventory src/test/java/org/riptide/inventory docs/docs/reference/exporter-enrichment.md
git commit -s -m "feat(inventory): poll: always marks an exporter entry for polling without flows"
```

---

### Task 5: A NetBox tag sets `poll: always` through discovery

**Files:**
- Modify: `src/main/java/org/riptide/discovery/NetboxDeviceSource.java:181-193`
- Modify: `src/main/java/org/riptide/discovery/DiscoveryConfig.java`
- Modify: `src/main/java/org/riptide/discovery/ExporterRenderer.java:58-121`
- Modify: `src/main/java/org/riptide/discovery/RenderedExporters.java`
- Modify: `src/main/java/org/riptide/discovery/ComposedInventoryDocument.java:432`
- Modify: `src/main/java/org/riptide/discovery/DiscoveryConfiguration.java`
- Modify: `docs/docs/reference/discovery.md:24-40`, `docs/docs/architecture/discovery.md:120-142`, `docs/docs/guides/discovery-netbox.md:89-115`
- Test: `src/test/java/org/riptide/discovery/NetboxDeviceSourceTest.java`, `ExporterRendererTest.java`, `ComposedInventoryDocumentTest.java`, `DiscoveryConfigTest.java`

**Interfaces:**
- Consumes: `TargetGroup(List<String> targets, Map<String, String> labels)`.
- Produces:
  - Label `__meta_netbox_tags` on every NetBox target group: the device's tag slugs joined by `,`, absent when the device has no tags.
  - `DiscoveryConfig.getPollAlwaysTag() -> String` (null by default).
  - `RenderedExporters(Map<String, String> byName, Set<String> pollAlways, int skipped)`.
  - `ExporterRenderer.render(groups, addressLabels, sourceName, pollAlwaysTag)` and the `EndpointGroups` overload likewise; the existing signatures delegate with `null`.
  - `ComposedInventoryDocument` emits `poll: always` for names in `pollAlways`.

- [ ] **Step 1: Write the failing tests**

`NetboxDeviceSourceTest`, extend the `device(...)` fixture helper with a `tags` array parameter (`String... tagSlugs`) that renders `"tags": [{"id": 1, "name": "x", "slug": "x"}, ...]`, keeping the old two-argument helper delegating with no tags. Add:

```java
    @Test
    void tagSlugsAreCarriedAsOneCommaJoinedLabel() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24", "flow-exporter", "snmp-poll")))).targets();
        assertThat(groups.get(0).labels()).containsEntry("__meta_netbox_tags", "flow-exporter,snmp-poll");
    }

    @Test
    void aDeviceWithoutTagsHasNoTagsLabel() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24")))).targets();
        assertThat(groups.get(0).labels()).doesNotContainKey("__meta_netbox_tags");
    }
```

`ExporterRendererTest`:

```java
    @Test
    void aGroupCarryingThePollAlwaysTagLandsInPollAlways() {
        final var tagged = group("10.0.0.1", Map.of("__meta_netbox_name", "sw-1", "__meta_netbox_tags", "a,snmp-poll,b"));
        final var plain = group("10.0.0.2", Map.of("__meta_netbox_name", "sw-2", "__meta_netbox_tags", "a"));
        final var rendered = ExporterRenderer.render(List.of(tagged, plain), ADDRESS_LABELS, "the endpoint", "snmp-poll");
        assertThat(rendered.pollAlways()).containsExactly("sw-1");
        assertThat(rendered.byName()).containsKeys("sw-1", "sw-2");
    }

    @Test
    void withoutAConfiguredTagNothingIsMarked() {
        final var tagged = group("10.0.0.1", Map.of("__meta_netbox_name", "sw-1", "__meta_netbox_tags", "snmp-poll"));
        assertThat(ExporterRenderer.render(List.of(tagged), ADDRESS_LABELS, "the endpoint", null).pollAlways()).isEmpty();
    }
```

`ComposedInventoryDocumentTest`, using the existing `composed(fileText, json)` helper or a variant that accepts a `DiscoveryConfig` with `pollAlwaysTag` set:

```java
    @Test
    void aTaggedDeviceComposesWithPollAlways() throws Exception {
        final String yaml = composedWithTag("snmp-poll", AGENTS_ONLY_FILE,
                page(null, device("sw-1", "10.0.0.1/24", "snmp-poll"), device("sw-2", "10.0.0.2/24")));
        assertThat(yaml).contains("sw-1:\n      address: 10.0.0.1\n      poll: always\n");
        assertThat(yaml).contains("sw-2:\n      address: 10.0.0.2\n");
        assertThat(yaml).doesNotContain("sw-2:\n      address: 10.0.0.2\n      poll");
        final var snapshot = InventoryLoader.parse(new SnmpProfilesConfig(Map.of(), Map.of()), yaml, "probe");
        assertThat(snapshot.exporterView().alwaysPolled()).extracting(ExporterEntry::name).containsExactly("sw-1");
    }
```

`DiscoveryConfigTest`: bind `riptide.discovery.poll-always-tag=snmp-poll` and assert `getPollAlwaysTag()`; bind nothing and assert null.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest='NetboxDeviceSourceTest,ExporterRendererTest,ComposedInventoryDocumentTest,DiscoveryConfigTest' -DfailIfNoTests=false`
Expected: compilation failures on the new signatures.

- [ ] **Step 3: Emit the tags label in `NetboxDeviceSource.group`**

```java
        final JsonNode tags = device.get("tags");
        if (tags != null && tags.isArray() && !tags.isEmpty()) {
            final String joined = StreamSupport.stream(tags.spliterator(), false)
                    .map(tag -> tag.path("slug").asText(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(","));
            if (!joined.isEmpty()) {
                labels.put(TAGS_LABEL, joined);
            }
        }
```

with `static final String TAGS_LABEL = "__meta_netbox_tags";`. Update the class javadoc at lines 39-44, which currently says tags are not read.

- [ ] **Step 4: Add the key to `DiscoveryConfig`**

```java
    /**
     * A NetBox tag slug. A discovered device carrying it is composed with {@code poll: always}.
     * Unset means no device is marked. Read by the exporter renderer; netbox-api only in this phase.
     */
    private String pollAlwaysTag;
```

- [ ] **Step 5: Carry it through `ExporterRenderer` and `RenderedExporters`**

`RenderedExporters` becomes `record RenderedExporters(Map<String, String> byName, Set<String> pollAlways, int skipped)` with a compact constructor copying both collections. In `render`, where `byName.put(name, address)` happens at line 121, add:

```java
            if (pollAlwaysTag != null && hasTag(group.labels().get(NetboxDeviceSource.TAGS_LABEL), pollAlwaysTag)) {
                pollAlways.add(name);
            }
```

```java
    private static boolean hasTag(final String joined, final String tag) {
        return joined != null && Arrays.asList(joined.split(",")).contains(tag);
    }
```

Rule for duplicate claims of one name: any claim with the tag marks the name. Existing `render` signatures delegate with `null` so callers outside discovery are untouched. `DiscoveryConfiguration` passes `config.getPollAlwaysTag()` wherever it calls `render`.

- [ ] **Step 6: Emit the key in `ComposedInventoryDocument.merge`**

Replace line 432 with:

```java
        rendered.byName().forEach((name, address) -> {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("address", address);
            if (rendered.pollAlways().contains(name)) {
                entry.put("poll", PollMode.ALWAYS.key());
            }
            exporters.put(name, entry);
        });
```

- [ ] **Step 7: Docs**

`reference/discovery.md` settings table: add

```markdown
| **`riptide.discovery.poll-always-tag`** | string | unset | `netbox-api` only. A device carrying this tag slug is composed with `poll: always`, so it is polled for SNMP metrics without waiting for a flow. See [Exporter enrichment](exporter-enrichment.md). |
```

`architecture/discovery.md`, "How a target becomes an exporter": one paragraph on the tags label and the `poll` key. `guides/discovery-netbox.md`: a subsection "Poll devices that send no flows" showing the tag on the device and the property, and the shard rule from the spec (one tag per collector in `filter`).

- [ ] **Step 8: Run the tests**

Run: `mvn -q test -Dtest='NetboxDeviceSourceTest,ExporterRendererTest,ComposedInventoryDocumentTest,DiscoveryConfigTest,DiscoveryWiringTest,MappedJsonEndToEndTest' -DfailIfNoTests=false`
Expected: pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/riptide/discovery src/test/java/org/riptide/discovery docs/docs
git commit -s -m "feat(discovery): a NetBox tag composes a device with poll: always"
```

---

### Task 6: The remote-write sink

**Files:**
- Modify: `pom.xml` (add `aircompressor` compile, `protobuf-java` test)
- Create: `src/main/java/org/riptide/metrics/MetricSink.java`
- Create: `src/main/java/org/riptide/metrics/NoopMetricSink.java`
- Create: `src/main/java/org/riptide/metrics/MetricsConfig.java`
- Create: `src/main/java/org/riptide/metrics/RemoteWriteEncoder.java`
- Create: `src/main/java/org/riptide/metrics/PrometheusRemoteWriteSink.java`
- Create: `src/main/java/org/riptide/configuration/MetricsConfiguration.java`
- Modify: `src/main/java/org/riptide/config/OutboundHttpTrust.java:22-26` (javadoc listing reached clients), `docs/docs/reference/outbound-tls.md`
- Modify: `src/main/resources/application.properties` (commented defaults)
- Test: `src/test/java/org/riptide/metrics/RemoteWriteEncoderTest.java`, `PrometheusRemoteWriteSinkTest.java`, `MetricsConfigTest.java`

**Interfaces:**
- Consumes: `Sample`, `SecretResolvers.resolve(SecretRef)`, `OutboundHttpTrust.socketFactory()`, `MetricRegistry`.
- Produces:
  - `interface MetricSink { void accept(List<Sample> samples); }`
  - `MetricsConfig` at prefix `riptide.metrics` with nested `RemoteWrite remoteWrite`: `String url`, `SecretRef bearerToken`, `Batch batch { int maxSamples = 5_000; Duration maxLatency = 2s }`, `int queueCapacity = 2_000_000`, `Duration shutdownGracePeriod = 5s`, `int maxAttempts = 3`; `boolean enabled()` (url non-blank); `void validate()`.
  - `RemoteWriteEncoder.encode(List<Sample>) -> byte[]` (protobuf, uncompressed) and `static byte[] snappy(byte[])`.
  - `PrometheusRemoteWriteSink(MetricsConfig.RemoteWrite, SecretResolvers, OutboundHttpTrust, MetricRegistry)` with `start()`, `stop()`, `accept(...)`; meters `metrics.sink.queueDepth` (gauge), `droppedSamples`, `failedSamples`, `sentSamples` (counters), `batchSize` (histogram), `flush` (timer).
  - Bean `MetricSink` from `MetricsConfiguration`: the remote-write sink when enabled, `NoopMetricSink` otherwise.

- [ ] **Step 1: Add the dependencies**

In `pom.xml` properties: `<aircompressor.version>0.27</aircompressor.version>` and `<protobuf.version>4.31.1</protobuf.version>`. Check Maven Central for the newest release of each before pinning and use that. Dependencies:

```xml
        <dependency>
            <groupId>io.airlift</groupId>
            <artifactId>aircompressor</artifactId>
            <version>${aircompressor.version}</version>
        </dependency>
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java</artifactId>
            <version>${protobuf.version}</version>
            <scope>test</scope>
        </dependency>
```

Run `mvn -q dependency:tree -Dincludes=io.airlift` and confirm aircompressor brings no transitive dependency. Then update the Nix `mvnHash` the way the memory note for Dependabot PRs describes, by running `nix build` and applying the hash from the failure.

- [ ] **Step 2: Write the failing encoder test**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import io.airlift.compress.snappy.SnappyDecompressor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteWriteEncoderTest {

    @Test
    void oneSampleBecomesOneTimeSeriesWithSortedLabelsAndTheNameFirst() throws IOException {
        final var sample = new Sample("ifHCInOctets", Map.of("ifName", "Gi0/0/3", "exporter", "edge-01", "ifIndex", "3"),
                1_000d, 1_700_000_000_000L);

        final List<Series> series = decode(RemoteWriteEncoder.encode(List.of(sample)));

        assertThat(series).hasSize(1);
        assertThat(series.get(0).labels).containsExactly(
                Map.entry("__name__", "ifHCInOctets"), Map.entry("exporter", "edge-01"),
                Map.entry("ifIndex", "3"), Map.entry("ifName", "Gi0/0/3"));
        assertThat(series.get(0).value).isEqualTo(1_000d);
        assertThat(series.get(0).timestamp).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void snappyRoundTripsThroughAnIndependentDecompressor() {
        final byte[] plain = RemoteWriteEncoder.encode(List.of(new Sample("x", Map.of("a", "b"), 1d, 2L)));
        final byte[] packed = RemoteWriteEncoder.snappy(plain);
        final byte[] out = new byte[plain.length];
        final int n = new SnappyDecompressor().decompress(packed, 0, packed.length, out, 0, out.length);
        assertThat(n).isEqualTo(plain.length);
        assertThat(out).isEqualTo(plain);
    }

    // A minimal decoder for the remote-write 1.0 schema, using protobuf-java's wire reader so the
    // varints and tags are checked by an implementation other than the one under test.
    private record Series(Map<String, String> labels, double value, long timestamp) {
    }

    private static List<Series> decode(final byte[] bytes) throws IOException {
        final CodedInputStream in = CodedInputStream.newInstance(bytes);
        final List<Series> result = new ArrayList<>();
        int tag;
        while ((tag = in.readTag()) != 0) {
            assertThat(WireFormat.getTagFieldNumber(tag)).as("WriteRequest.timeseries").isEqualTo(1);
            final CodedInputStream ts = CodedInputStream.newInstance(in.readByteArray());
            final Map<String, String> labels = new LinkedHashMap<>();
            double value = Double.NaN;
            long timestamp = 0;
            int tsTag;
            while ((tsTag = ts.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tsTag)) {
                    case 1 -> {
                        final CodedInputStream label = CodedInputStream.newInstance(ts.readByteArray());
                        String name = null;
                        String val = null;
                        int lt;
                        while ((lt = label.readTag()) != 0) {
                            if (WireFormat.getTagFieldNumber(lt) == 1) {
                                name = label.readString();
                            } else {
                                val = label.readString();
                            }
                        }
                        labels.put(name, val);
                    }
                    case 2 -> {
                        final CodedInputStream s = CodedInputStream.newInstance(ts.readByteArray());
                        int st;
                        while ((st = s.readTag()) != 0) {
                            if (WireFormat.getTagFieldNumber(st) == 1) {
                                value = s.readDouble();
                            } else {
                                timestamp = s.readInt64();
                            }
                        }
                    }
                    default -> throw new AssertionError("unexpected field " + tsTag);
                }
            }
            result.add(new Series(labels, value, timestamp));
        }
        return result;
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -q test -Dtest=RemoteWriteEncoderTest -DfailIfNoTests=false`
Expected: compilation failure, `RemoteWriteEncoder` missing.

- [ ] **Step 4: Write `RemoteWriteEncoder`**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import io.airlift.compress.snappy.SnappyCompressor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prometheus remote-write 1.0 {@code WriteRequest}, hand-encoded. The schema is four messages
 * and nine fields, so the encoder is smaller than the dependency it replaces, in keeping with
 * {@code PrometheusExposition}'s policy of not pulling a bridge library for a small format.
 *
 * <pre>
 * WriteRequest { repeated TimeSeries timeseries = 1; }
 * TimeSeries   { repeated Label labels = 1; repeated Sample samples = 2; }
 * Label        { string name = 1; string value = 2; }
 * Sample       { double value = 1; int64 timestamp = 2; }
 * </pre>
 *
 * Labels are sorted by name, as the protocol requires, and {@code __name__} carries the metric.
 */
public final class RemoteWriteEncoder {

    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_VARINT = 0;

    private RemoteWriteEncoder() {
    }

    public static byte[] encode(final List<Sample> samples) {
        final ByteArrayOutputStream request = new ByteArrayOutputStream(samples.size() * 96);
        for (final Sample sample : samples) {
            writeBytes(request, 1, timeSeries(sample));
        }
        return request.toByteArray();
    }

    public static byte[] snappy(final byte[] plain) {
        final SnappyCompressor compressor = new SnappyCompressor();
        final byte[] out = new byte[compressor.maxCompressedLength(plain.length)];
        final int n = compressor.compress(plain, 0, plain.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }

    private static byte[] timeSeries(final Sample sample) {
        final ByteArrayOutputStream ts = new ByteArrayOutputStream(96);
        final Map<String, String> labels = new TreeMap<>(sample.labels());
        labels.put("__name__", sample.name());
        for (final Map.Entry<String, String> label : labels.entrySet()) {
            final ByteArrayOutputStream l = new ByteArrayOutputStream(32);
            writeString(l, 1, label.getKey());
            writeString(l, 2, label.getValue());
            writeBytes(ts, 1, l.toByteArray());
        }
        final ByteArrayOutputStream s = new ByteArrayOutputStream(20);
        writeTag(s, 1, WIRE_FIXED64);
        long bits = Double.doubleToLongBits(sample.value());
        for (int i = 0; i < 8; i++) {
            s.write((int) (bits & 0xFF));
            bits >>>= 8;
        }
        writeTag(s, 2, WIRE_VARINT);
        writeVarint(s, sample.timestampMs());
        writeBytes(ts, 2, s.toByteArray());
        return ts.toByteArray();
    }

    private static void writeString(final ByteArrayOutputStream out, final int field, final String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(final ByteArrayOutputStream out, final int field, final byte[] value) {
        writeTag(out, field, WIRE_LENGTH_DELIMITED);
        writeVarint(out, value.length);
        out.writeBytes(value);
    }

    private static void writeTag(final ByteArrayOutputStream out, final int field, final int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarint(final ByteArrayOutputStream out, final long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
```

If the pinned aircompressor release moved the class to `io.airlift.compress.v3.snappy`, adjust both imports; the API is the same.

- [ ] **Step 5: Run the encoder test**

Run: `mvn -q test -Dtest=RemoteWriteEncoderTest -DfailIfNoTests=false`
Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 6: Write the failing sink and config tests**

`MetricsConfigTest`: bind with Spring's `Binder` (see `SnmpPollConfigTest` for the idiom) and assert the defaults 5000, `PT2S`, 2000000, `PT5S`, 3; assert `enabled()` is false with no URL and true with one; assert `validate()` names `riptide.metrics.remote-write.batch.max-samples` when it is 0 and `riptide.metrics.remote-write.queue-capacity` when negative.

`PrometheusRemoteWriteSinkTest`, against the JDK `HttpServer`:

```java
    @Test
    void aBatchIsPostedWithTheRemoteWriteHeadersAndTheBearerToken() throws Exception {
        final var received = new LinkedBlockingQueue<Received>();
        try (var server = stub(exchange -> {
            received.add(Received.of(exchange));
            exchange.sendResponseHeaders(204, -1);
        })) {
            final var config = remoteWrite(server.url(), "plain-token");
            final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), new MetricRegistry());
            sink.start();
            sink.accept(List.of(new Sample("x", Map.of("a", "b"), 1d, 2L)));
            final Received r = received.poll(5, TimeUnit.SECONDS);
            sink.stop();

            assertThat(r).isNotNull();
            assertThat(r.path()).isEqualTo("/insert/0:0/prometheus/api/v1/write");
            assertThat(r.headers()).containsEntry("Content-Type", "application/x-protobuf")
                    .containsEntry("Content-Encoding", "snappy")
                    .containsEntry("X-Prometheus-Remote-Write-Version", "0.1.0")
                    .containsEntry("Authorization", "Bearer plain-token");
            final byte[] plain = new byte[4096];
            final int n = new SnappyDecompressor().decompress(r.body(), 0, r.body().length, plain, 0, plain.length);
            assertThat(n).isGreaterThan(0);
        }
    }

    @Test
    void aFiveHundredIsRetriedAndAFourHundredIsDroppedAndCounted() throws Exception {
        // Review Focus 4
        final var statuses = new LinkedBlockingQueue<>(List.of(503, 204, 400, 204));
        final var posts = new AtomicInteger();
        try (var server = stub(exchange -> {
            posts.incrementAndGet();
            exchange.sendResponseHeaders(statuses.poll(), -1);
        })) {
            final var metrics = new MetricRegistry();
            final var config = remoteWrite(server.url(), null);
            config.getBatch().setMaxSamples(1);
            config.setRetryBackoff(Duration.ofMillis(10));
            final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
            sink.start();
            sink.accept(List.of(new Sample("a", Map.of(), 1d, 1L)));   // 503 then 204
            sink.accept(List.of(new Sample("b", Map.of(), 1d, 1L)));   // 400, dropped
            sink.accept(List.of(new Sample("c", Map.of(), 1d, 1L)));   // 204
            awaitPosts(posts, 4);
            sink.stop();

            assertThat(metrics.counter("metrics.sink.failedSamples").getCount()).isEqualTo(1);
            assertThat(metrics.counter("metrics.sink.sentSamples").getCount()).isEqualTo(2);
        }
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfBlocking() {
        final var metrics = new MetricRegistry();
        final var config = remoteWrite("http://127.0.0.1:9/api/v1/write", null);
        config.setQueueCapacity(2);
        final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
        // not started: nothing drains
        sink.accept(List.of(sample(), sample(), sample(), sample()));
        assertThat(metrics.counter("metrics.sink.droppedSamples").getCount()).isEqualTo(2);
        assertThat(metrics.gauge("metrics.sink.queueDepth").getValue()).isEqualTo(2);
    }
```

The `stub(...)` helper wraps `HttpServerConfig.ensureApplied()`, `HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)`, a context at `/insert/0:0/prometheus/api/v1/write`, and returns an `AutoCloseable` with `url()`. `Received.of(exchange)` reads path, headers (first value each) and the body bytes.

- [ ] **Step 7: Run to verify they fail**

Run: `mvn -q test -Dtest='PrometheusRemoteWriteSinkTest,MetricsConfigTest' -DfailIfNoTests=false`
Expected: compilation failures.

- [ ] **Step 8: Write `MetricSink`, `NoopMetricSink`, `MetricsConfig`**

```java
/** Receives samples from the poller. Implementations must not block the caller. */
public interface MetricSink {
    void accept(List<Sample> samples);
}
```

```java
/** The sink when {@code riptide.metrics.remote-write.url} is unset: samples are discarded silently. */
public final class NoopMetricSink implements MetricSink {
    @Override
    public void accept(final List<Sample> samples) {
        // nothing to do
    }
}
```

```java
@Data
@ConfigurationProperties(prefix = "riptide.metrics")
public final class MetricsConfig {

    private RemoteWrite remoteWrite = new RemoteWrite();

    @Data
    public static final class RemoteWrite {
        /** The full write URL, tenant path included for a VictoriaMetrics cluster. Unset disables the sink. */
        private String url;
        /** Sent as {@code Authorization: Bearer <token>} when set. Resolved on every flush so a rotation takes effect. */
        private SecretRef bearerToken;
        private Batch batch = new Batch();
        private int queueCapacity = 2_000_000;
        private Duration shutdownGracePeriod = Duration.ofSeconds(5);
        private int maxAttempts = 3;
        private Duration retryBackoff = Duration.ofSeconds(1);

        public boolean enabled() {
            return this.url != null && !this.url.isBlank();
        }

        public void validate() {
            if (this.batch.maxSamples <= 0) {
                throw new IllegalArgumentException("riptide.metrics.remote-write.batch.max-samples must be > 0 (got " + this.batch.maxSamples + ")");
            }
            if (this.batch.maxLatency == null || this.batch.maxLatency.isZero() || this.batch.maxLatency.isNegative()) {
                throw new IllegalArgumentException("riptide.metrics.remote-write.batch.max-latency must be > 0 (got " + this.batch.maxLatency + ")");
            }
            if (this.queueCapacity <= 0) {
                throw new IllegalArgumentException("riptide.metrics.remote-write.queue-capacity must be > 0 (got " + this.queueCapacity + ")");
            }
            if (this.maxAttempts <= 0) {
                throw new IllegalArgumentException("riptide.metrics.remote-write.max-attempts must be > 0 (got " + this.maxAttempts + ")");
            }
            if (this.shutdownGracePeriod == null || this.shutdownGracePeriod.compareTo(this.batch.maxLatency.multipliedBy(2)) < 0) {
                throw new IllegalArgumentException("riptide.metrics.remote-write.shutdown-grace-period must be at least twice batch.max-latency");
            }
            if (this.enabled()) {
                try {
                    new URI(this.url).toURL();
                } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("riptide.metrics.remote-write.url is not a URL: " + e.getMessage(), e);
                }
            }
        }
    }

    @Data
    public static final class Batch {
        private int maxSamples = 5_000;
        private Duration maxLatency = Duration.ofSeconds(2);
    }
}
```

Two keys beyond the spec table, `max-attempts` and `retry-backoff`, are needed for the retry rule in the spec ("retries with backoff on 5xx and 429"). Consumer: the sink's flusher. Proving test: the 503-then-204 test above. Add both to the reference page in Task 8.

- [ ] **Step 9: Write `PrometheusRemoteWriteSink`**

Mirror `BatchingFlowRepository` (queue, `Queues.drain`, drop-not-block, thread naming, shutdown sweep), with the flush doing the POST:

```java
@Slf4j
public final class PrometheusRemoteWriteSink implements MetricSink {

    private static final long OFFER_TIMEOUT_MS = 100;
    private static final long DROP_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final MetricsConfig.RemoteWrite config;
    private final SecretResolvers secretResolvers;
    private final OutboundHttpTrust trust;
    private final URL url;
    private final LinkedBlockingQueue<Sample> queue;
    private final Counter dropped;
    private final Counter failed;
    private final Counter sent;
    private final Histogram batchSize;
    private final Timer flushTimer;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong lastDropWarnNanos = new AtomicLong();
    private volatile Thread thread;

    public PrometheusRemoteWriteSink(final MetricsConfig.RemoteWrite config, final SecretResolvers secretResolvers,
                                     final OutboundHttpTrust trust, final MetricRegistry metrics) {
        config.validate();
        this.config = config;
        this.secretResolvers = secretResolvers;
        this.trust = trust;
        try {
            this.url = new URI(config.getUrl()).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IllegalArgumentException("riptide.metrics.remote-write.url is not a URL: " + e.getMessage(), e);
        }
        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        this.dropped = metrics.counter(MetricRegistry.name("metrics", "sink", "droppedSamples"));
        this.failed = metrics.counter(MetricRegistry.name("metrics", "sink", "failedSamples"));
        this.sent = metrics.counter(MetricRegistry.name("metrics", "sink", "sentSamples"));
        this.batchSize = metrics.histogram(MetricRegistry.name("metrics", "sink", "batchSize"));
        this.flushTimer = metrics.timer(MetricRegistry.name("metrics", "sink", "flush"));
        final String depth = MetricRegistry.name("metrics", "sink", "queueDepth");
        metrics.remove(depth);
        metrics.register(depth, (Gauge<Integer>) this.queue::size);
    }

    @Override
    public void accept(final List<Sample> samples) {
        // same shape as BatchingFlowRepository.persist: one 100 ms offer budget for the whole call,
        // then non-blocking offers, first refusal drops the rest
        ...
    }

    public void start() { /* as BatchingFlowRepository.start, thread name "remote-write-flusher" */ }

    private void flushLoop() { /* as BatchingFlowRepository.flushLoop, calling flush(batch) */ }

    private void flush(final List<Sample> batch) {
        this.batchSize.update(batch.size());
        try (var ignored = this.flushTimer.time()) {
            final byte[] body = RemoteWriteEncoder.snappy(RemoteWriteEncoder.encode(batch));
            for (int attempt = 1; ; attempt++) {
                final int status = post(body);
                if (status >= 200 && status < 300) {
                    this.sent.inc(batch.size());
                    return;
                }
                final boolean retryable = status == 429 || status >= 500 || status < 0;
                if (!retryable || attempt >= this.config.getMaxAttempts()) {
                    this.failed.inc(batch.size());
                    log.warn("Remote write of {} samples failed with status {} after {} attempt(s); batch dropped",
                            batch.size(), status, attempt);
                    return;
                }
                sleep(this.config.getRetryBackoff().multipliedBy(1L << (attempt - 1)));
            }
        }
    }

    /** Returns the HTTP status, or -1 when the request could not be made at all. */
    private int post(final byte[] body) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
            final SSLSocketFactory factory = this.trust.socketFactory();
            if (factory != null && connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(factory);
            }
            connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/x-protobuf");
            connection.setRequestProperty("Content-Encoding", "snappy");
            connection.setRequestProperty("X-Prometheus-Remote-Write-Version", "0.1.0");
            final String token = this.secretResolvers.resolve(this.config.getBearerToken());
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            final int status = connection.getResponseCode();
            connection.getInputStream().close();  // let the connection return to the keep-alive pool
            return status;
        } catch (final IOException e) {
            log.debug("Remote write POST failed: {}", e.getMessage());
            return -1;
        }
    }

    public void stop() { /* as BatchingFlowRepository.stop: join grace, interrupt, sweep, unregister gauge */ }
}
```

On a non-2xx status `getInputStream()` throws; read `getErrorStream()` to drain it instead. Write the elided methods by copying the corresponding `BatchingFlowRepository` methods and renaming the types; do not import from that class.

- [ ] **Step 10: Wire the bean**

```java
@Configuration
public class MetricsConfiguration {

    @Bean(destroyMethod = "stop")
    public MetricSink metricSink(final MetricsConfig config, final SecretResolvers secretResolvers,
                                 final OutboundHttpTrust trust, final MetricRegistry metrics) {
        if (!config.getRemoteWrite().enabled()) {
            return new NoopMetricSink();
        }
        final var sink = new PrometheusRemoteWriteSink(config.getRemoteWrite(), secretResolvers, trust, metrics);
        sink.start();
        return sink;
    }
}
```

`destroyMethod = "stop"` on a `NoopMetricSink` would fail, so give `MetricSink` a `default void stop() {}` and have the remote-write sink override it. Check the Checkstyle `ImportControl` file allows `org.riptide.configuration` to import `org.riptide.metrics`; add the rule if not. Update the `OutboundHttpTrust` javadoc and `docs/docs/reference/outbound-tls.md` to list the remote-write URL among the connections the CA bundle reaches. Add commented defaults to `application.properties` under a `# Metrics remote-write` heading.

- [ ] **Step 11: Run the tests**

Run: `mvn -q test -Dtest='RemoteWriteEncoderTest,PrometheusRemoteWriteSinkTest,MetricsConfigTest' -DfailIfNoTests=false`
Expected: pass.

- [ ] **Step 12: Commit**

```bash
git add pom.xml nix src/main/java/org/riptide/metrics src/main/java/org/riptide/configuration/MetricsConfiguration.java src/main/java/org/riptide/config/OutboundHttpTrust.java src/main/resources/application.properties src/test/java/org/riptide/metrics docs/docs/reference/outbound-tls.md
git commit -s -m "feat(metrics): Prometheus remote-write sink with a bounded queue"
```

---

### Task 7: The poller collects, emits samples and registers inventory entries

**Files:**
- Modify: `src/main/java/org/riptide/snmp/InterfaceSnapshotPoller.java` (Registration, constructor, `walk`, `tick`, `refreshRegistrations`, class javadoc lines 33-57)
- Modify: `src/main/java/org/riptide/snmp/SnmpPollConfig.java` (`poolWidth` default when collecting is decided in the poller, config unchanged)
- Test: `src/test/java/org/riptide/snmp/InterfaceSnapshotPollerTest.java`

**Interfaces:**
- Consumes: `SnmpService.collect`, `SampleMapper`, `MetricSink`, `ExporterView.alwaysPolled()`, `AgentEndpointFactory.endpointFor`, `DaemonConfig.IdentityConfig` (tenant, organisation, zone), `SnmpEndpoint.getCollections()`.
- Produces:
  - Constructor `InterfaceSnapshotPoller(SnmpService, SnmpPollConfig, MetricRegistry, Inventory, MetricSink, DaemonConfig)` (Spring) and the package-private seam with `LongSupplier nanoTime, boolean startScheduler, LongSupplier wallClockMs`.
  - `enum RegistrationSource { FLOW_ARRIVAL, INVENTORY }` on `Registration`.
  - Meters: `snmp.poller.inventoryRegistered` (gauge), `snmp.poller.inventoryRefused` (gauge, 0 or the refused count), `snmp.poller.samplesEmitted` (meter), `snmp.poller.collectsFailed` (meter).
  - `refreshRegistrations()` also runs the inventory sweep; it is called once at the end of construction when the scheduler starts.

- [ ] **Step 1: Write the failing poller tests**

Extend `InterfaceSnapshotPollerTest`. `FakeSnmp` gains a `collect` implementation returning one row `1 -> info {ifName=eth0, ifAlias=uplink, ifHighSpeed=1000}, values {ifHCInOctets=<walks so far * 100>}` and a `collects` counter; the `poller(...)` helper takes a `RecordingSink` (a `MetricSink` that appends to a list) and a `DaemonConfig` with tenant `t1`, organisation `o1`, zone `z1`. `PROFILES` gains a polling profile `counters` with `refreshInterval PT1M`, `collect [if-mib-interfaces]`.

```java
    @Test
    void aCollectingProfileWalksThroughCollectAndEmitsSamplesWithTheIdentityLabels() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var endpoint = endpoint("10.0.0.10", "polling: counters");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(snmp.collects.get()).isEqualTo(1);
        assertThat(snmp.walks.get()).as("no second walk for enrichment").isEqualTo(0);
        assertThat(sink.samples).extracting(Sample::name).contains("ifHCInOctets", "riptide_interface_info");
        final Sample octets = sink.samples.stream().filter(s -> s.name().equals("ifHCInOctets")).findFirst().orElseThrow();
        assertThat(octets.labels()).containsEntry("tenant", "t1").containsEntry("organisation", "o1")
                .containsEntry("zone", "z1").containsEntry("exporter_address", "10.0.0.10")
                .containsEntry("ifIndex", "1").containsEntry("ifName", "eth0");
        // and the enrichment snapshot was refreshed from the same rows
        assertThat(poller.trackAndResolve(endpoint, 1)).contains(new IfInfo("eth0", "uplink", 1000L));
    }

    @Test
    void theExporterLabelIsTheInventoryNameWhenAnEntryCoversTheAddress() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: public, polling: counters }
                  exporters:
                    edge-01: { address: 10.0.0.10 }
                """));
        final var endpoint = resolveOnly("10.0.0.10");
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);
        assertThat(sink.samples.get(0).labels()).containsEntry("exporter", "edge-01");
    }

    @Test
    void aFailedCollectEmitsNothingAndBacksOff() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.timeout = true;
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var endpoint = endpoint("10.0.0.10", "polling: counters");
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);
        assertThat(sink.samples).isEmpty();
        assertThat(this.metrics.meter("snmp.poller.collectsFailed").getCount()).isEqualTo(1);
    }

    @Test
    void aPollAlwaysEntryIsRegisteredFromTheInventoryAndSurvivesSilence() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: public, polling: counters }
                  exporters:
                    silent-switch: { address: 10.0.0.20, poll: always }
                """));
        poller.refreshRegistrations();
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(1);
        // silent for far longer than deregisterAfter intervals: still polled
        advanceMs(60_000L * 10);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
        assertThat(sink.samples).extracting(s -> s.labels().get("exporter")).contains("silent-switch");
    }

    @Test
    void anAlwaysEntryWithoutACoveringAgentRangeIsWarnedAndSkipped() throws Exception {
        // Review Focus 2
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config(), new RecordingSink());
        serve(parse("""
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    orphan: { address: 10.9.9.9, poll: always }
                """));
        try (var logs = LogCapture.of(InterfaceSnapshotPoller.class)) {
            poller.refreshRegistrations();
            assertThat(logs.messages()).anySatisfy(m -> assertThat(m).contains("orphan").contains("no agent range"));
        }
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(snmp.collects.get() + snmp.walks.get()).isEqualTo(0);
    }

    @Test
    void anAlwaysEntryRemovedFromTheInventoryFallsBackToFlowLifecycle() throws Exception {
        // Review Focus 5
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config(), new RecordingSink());
        serve(parse(ALWAYS_INVENTORY));
        poller.refreshRegistrations();
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        serve(parse(ALWAYS_INVENTORY.replace(", poll: always", "")));
        poller.refreshRegistrations();
        // no flows and silent for deregisterAfter intervals: now it goes
        advanceMs(60_000L * 3 + 1_000);
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(0);
    }

    @Test
    void moreAlwaysEntriesThanMaxExportersRefusesTheWholeSet() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setMaxExporters(2);
        final var poller = poller(snmp, config, new RecordingSink());
        serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: public, polling: counters }
                  exporters:
                    a: { address: 10.0.0.1, poll: always }
                    b: { address: 10.0.0.2, poll: always }
                    c: { address: 10.0.0.3, poll: always }
                """));
        try (var logs = LogCapture.of(InterfaceSnapshotPoller.class)) {
            poller.refreshRegistrations();
            assertThat(logs.messages()).anySatisfy(m -> assertThat(m).contains("3").contains("riptide.snmp.poll.max-exporters"));
        }
        assertThat(this.metrics.gauge("snmp.poller.inventoryRefused").getValue()).isEqualTo(3);
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(0);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=InterfaceSnapshotPollerTest -DfailIfNoTests=false`
Expected: compilation failures on the new constructor and `RecordingSink`.

- [ ] **Step 3: Extend `Registration` and the constructor**

Add to `Registration`: `volatile RegistrationSource source;` and `volatile String exporterName;`. Add fields to the poller: `MetricSink sink`, `Map<String, String> identityLabels` (tenant, organisation, zone from `DaemonConfig.getIdentity()`), `LongSupplier wallClockMs`, an `AtomicInteger inventoryRegistered`, `AtomicInteger inventoryRefused`, meters `samplesEmitted` and `collectsFailed`, and gauges for the two integers. The Spring constructor passes `System::currentTimeMillis`. When `startScheduler` is true, call `refreshRegistrations()` at the end of the constructor so `always` entries are registered at boot; the inventory bean is fully loaded by then because it is a constructor dependency.

- [ ] **Step 4: Collect and emit in `walk`**

Replace the single `snmpService.walkInterfaces(...)` call:

```java
        final List<CollectionDefinition> definitions = registration.endpoint.getCollections();
        final long wallMs = this.wallClockMs.getAsLong();
        final SnmpService.InterfaceTable table;
        final List<Sample> samples = new ArrayList<>();
        if (definitions.isEmpty()) {
            table = this.snmpService.walkInterfaces(registration.endpoint);
        } else {
            final Duration interval = Duration.ofMillis(refreshMsFor(registration.endpoint));
            final Map<Integer, IfInfo> rows = new TreeMap<>();
            boolean failed = false;
            for (final CollectionDefinition definition : definitions) {
                final CollectedTable collected = this.snmpService.collect(registration.endpoint, definition,
                        definition.walkBudget(interval));
                if (collected.walkFailed()) {
                    failed = true;
                    this.collectsFailed.mark();
                    break;
                }
                collected.rows().forEach((index, row) -> rows.put(index, SampleMapper.toIfInfo(row)));
                samples.addAll(SampleMapper.toSamples(definition, labelsFor(registration), collected, wallMs));
            }
            table = new SnmpService.InterfaceTable(failed ? Map.of() : rows, failed);
        }
```

then the existing success path, plus after the snapshot is stored:

```java
                if (!samples.isEmpty()) {
                    this.sink.accept(samples);
                    this.samplesEmitted.mark(samples.size());
                }
```

`labelsFor(registration)` builds a `HashMap` from `identityLabels`, adds `exporter_address` (the host address string) and `exporter` (the `exporterName` if set, else the address). `exporterName` is set at registration and re-resolution from `snapshot.exporterView().match(new ExporterIdentity.NetflowIpfix(address, 0L)).map(ExporterEntry::name)`.

- [ ] **Step 5: The inventory sweep in `refreshRegistrations`**

After the existing per-registration loop:

```java
        final List<ExporterEntry> always = snapshot.exporterView().alwaysPolled();
        final int maxExporters = this.config.getMaxExporters();
        if (always.size() > maxExporters) {
            this.inventoryRefused.set(always.size());
            this.inventoryRegistered.set(0);
            log.error("The inventory marks {} entries poll: always but riptide.snmp.poll.max-exporters is {}: none of them is polled. Raise the limit or narrow the discovery filter.",
                    always.size(), maxExporters);
        } else {
            this.inventoryRefused.set(0);
            int registered = 0;
            final Set<InetSocketAddress> wanted = new HashSet<>();
            for (final ExporterEntry entry : always) {
                final InetAddress address = entry.address().getAddress().toInetAddress();
                final Optional<SnmpEndpoint> endpoint = snapshot.agentView()
                        .match(new ExporterIdentity.NetflowIpfix(address, 0L))
                        .flatMap(agent -> AgentEndpointFactory.endpointFor(agent, entry.address()));
                if (endpoint.isEmpty()) {
                    log.warn("Exporter '{}' ({}) is poll: always but no agent range with credentials covers it; not polled",
                            entry.name(), entry.address());
                    continue;
                }
                final Registration registration = register(endpoint.get(), now);
                if (registration == null) {
                    continue;   // the flow-arrival cap; counted on rejectedLookups
                }
                registration.source = RegistrationSource.INVENTORY;
                registration.exporterName = entry.name();
                wanted.add(endpoint.get().getInetSocketAddress());
                registered++;
            }
            for (final Registration registration : this.registrations.values()) {
                if (registration.source == RegistrationSource.INVENTORY
                        && !wanted.contains(registration.endpoint.getInetSocketAddress())) {
                    registration.source = RegistrationSource.FLOW_ARRIVAL;
                    registration.lastSeenNanos = now;   // silence counts from here, not from before
                }
            }
            this.inventoryRegistered.set(registered);
        }
```

`register` marks new registrations `FLOW_ARRIVAL` by default. In `tick`, guard the silence check with `registration.source == RegistrationSource.FLOW_ARRIVAL`. An entry dropped from the inventory is downgraded rather than removed so that a device still sending flows keeps its registration (Review Focus 5).

- [ ] **Step 6: Update the class javadoc**

Lines 33-57 describe flow arrival as the only registration path. Rewrite the first paragraph to name both sources and point at `PollMode`, keep the three structural properties, and add a fourth: "An inventory-registered exporter is never removed for silence; only the inventory removes it."

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='InterfaceSnapshotPollerTest,SnmpEnricherTest,ConfigFileReloaderTest,InventoryFileReloaderTest' -DfailIfNoTests=false`
Expected: pass. The two reloader tests construct the poller and must be updated for the new constructor.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/riptide/snmp src/test/java/org/riptide
git commit -s -m "feat(snmp): the poller collects counters, emits samples and registers poll: always entries"
```

---

### Task 8: Reference and operations docs

**Files:**
- Create: `docs/docs/reference/snmp-metrics.md`
- Modify: `docs/docs/reference/metrics.md` (new series), `docs/docs/reference/agent-configuration.md:25-36` (profile `collect`, `deregister-after` wording, `max-exporters` wording)
- Modify: `docs/docs/architecture/enrichment.md` or the poller section that says walks are enrichment-only
- Local only, no commit: `openspec/specs/snmp-interface-polling/spec.md` requirement rationale and the "silent exporter" scenario

- [ ] **Step 1: Write `reference/snmp-metrics.md`**

Sections: `## Settings` (a `Name | Type | Default | Description` table with every `riptide.metrics.remote-write.*` key including `max-attempts` and `retry-backoff`, and the profile `collect`), `## Series` (a table of the fourteen counter and gauge series and `riptide_interface_info`, with labels), `## VictoriaMetrics cluster URL` (the tenant path example from the spec), `## Deduplication` (the `-dedup.minScrapeInterval` statement from the spec, and the shard rule), `## Messages` (the validation messages verbatim). Front matter as the sibling pages, `sidebar_position` after `metrics.md`.

- [ ] **Step 2: Extend `reference/metrics.md`**

Add rows for `metrics.sink.queueDepth`, `droppedSamples`, `failedSamples`, `sentSamples`, `batchSize`, `flush`, `snmp.collects`, `snmp.collectDuration`, `snmp.collects.failed`, `snmp.poller.inventoryRegistered`, `snmp.poller.inventoryRefused`, `snmp.poller.samplesEmitted`, `snmp.poller.collectsFailed` in the table style the page already uses, with the Prometheus-rendered name.

- [ ] **Step 3: Agent configuration page**

In the polling profile table add `collect` (list of strings, empty, "collection definitions to poll; `if-mib-interfaces` is the only one; when set, `refresh-interval` is the counter interval and the walk budget is 80 percent of it"). Reword `deregister-after` to say it applies to flow-registered exporters only, and `max-exporters` to say it also caps `poll: always` entries and how the refusal shows.

- [ ] **Step 4: Rewrite the openspec sibling locally**

In `openspec/specs/snmp-interface-polling/spec.md`, change the first requirement's rationale to say enumeration is opt-in per entry through `poll: always`, scope the "silent exporter is never polled" scenario to `poll: on-flow`, and add a scenario "a poll: always entry is walked without a flow". This file is untracked; note in the PR description that it was changed locally.

- [ ] **Step 5: Verify the docs build**

Run: `cd docs && npm run build 2>&1 | tail -5`
Expected: `[SUCCESS] Generated static files in "build".` with no broken-link warning. Check that a test does not pin the docs sidebar by filename (grep `snmp-metrics` and `metrics.md` under `src/test` and `docs/lint`).

- [ ] **Step 6: Commit**

```bash
git add docs/docs
git commit -s -m "docs: SNMP metrics reference, sink and poller series, poll: always"
```

---

### Task 9: End to end against VictoriaMetrics

**Files:**
- Create: `.github/e2e-images/victoriametrics.Dockerfile`
- Modify: `src/test/java/org/riptide/e2e/ContainerImages.java`
- Create: `src/test/java/org/riptide/snmp/SnmpMetricsIT.java`

**Interfaces:**
- Consumes: `TestSnmpAgent.setCounter`, `InterfaceSnapshotPoller` package-private constructor, `PrometheusRemoteWriteSink`, `ContainerImages.fromLine`.

- [ ] **Step 1: Pin the image**

Run `docker pull victoriametrics/victoria-metrics:v1.<newest stable>` and `docker inspect --format '{{index .RepoDigests 0}}' victoriametrics/victoria-metrics:v1.<newest stable>`. Write:

```dockerfile
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the VictoriaMetrics image used by SnmpMetricsIT. Pinned by digest
# as well as tag so Dependabot updates both parts. Not built; only the FROM line is read.
FROM victoriametrics/victoria-metrics:v1.XXX.0@sha256:<digest>
```

Add `public static String victoriametrics() { return fromLine("victoriametrics.Dockerfile"); }` to `ContainerImages`. Add the image to the CI pre-pull step in `.github/workflows/build.yml:161-177` if that step enumerates images by name rather than by directory listing.

- [ ] **Step 2: Write the IT**

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.e2e.ContainerImages;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.metrics.MetricsConfig;
import org.riptide.metrics.PrometheusRemoteWriteSink;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class SnmpMetricsIT {

    // latencyOffset: VictoriaMetrics hides the newest 30 s from queries by default, which would
    // make a short test wait for nothing
    @Container
    private static final GenericContainer<?> VM = new GenericContainer<>(ContainerImages.victoriametrics())
            .withCommand("-search.latencyOffset=1s", "-retentionPeriod=1d")
            .withExposedPorts(8428)
            .waitingFor(Wait.forHttp("/health").forPort(8428).forStatusCode(200));

    private static final int EXPORTER_PORT = 12500;
    private static final int SILENT_PORT = 12501;

    private TestSnmpAgent exporter;
    private TestSnmpAgent silent;
    private DefaultSnmpService snmp;
    private PrometheusRemoteWriteSink sink;
    private InterfaceSnapshotPoller poller;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start(@TempDir final Path dir) throws Exception {
        this.exporter = new TestSnmpAgent("127.0.0.1/" + EXPORTER_PORT, dir.resolve("a"));
        this.silent = new TestSnmpAgent("127.0.0.1/" + SILENT_PORT, dir.resolve("b"));
        for (final TestSnmpAgent agent : List.of(this.exporter, this.silent)) {
            agent.start();
            agent.registerIfTable();
            agent.registerIfXTable();
        }
        final var metrics = new MetricRegistry();
        this.snmp = new DefaultSnmpService(SecretResolvers.defaults(), metrics);

        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setUrl(vmUrl("/api/v1/write"));
        remoteWrite.getBatch().setMaxLatency(Duration.ofMillis(200));
        this.sink = new PrometheusRemoteWriteSink(remoteWrite, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
        this.sink.start();

        final var profiles = new SnmpProfilesConfig(Map.of("public", TestCredentials.communityV2c(TestSnmpAgent.COMMUNITY)),
                Map.of("counters", new PollingProfile(Duration.ofSeconds(2), Duration.ofMinutes(1), 500, 1,
                        List.of("if-mib-interfaces"))));
        final var inventory = new Inventory(profiles, new FileInventoryDocument(new InventoryConfig()));
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      127.0.0.1/32: { credentials: public, polling: counters, port: %d }
                  exporters:
                    silent-switch: { address: 127.0.0.1, poll: always }
                """.formatted(SILENT_PORT), "it.yaml"));
        // one agent range per port is not expressible, so the exporter and the silent device share
        // the loopback address and differ by port: the exporter's endpoint is built by hand below
        final var pollConfig = new SnmpPollConfig();
        final var daemon = new org.riptide.config.DaemonConfig();
        this.poller = new InterfaceSnapshotPoller(this.snmp, pollConfig, metrics, inventory, this.sink, daemon,
                System::nanoTime, true, System::currentTimeMillis);
    }

    @AfterEach
    void stop() {
        this.poller.stop();
        this.sink.stop();
        this.snmp.close();
        this.exporter.stop();
        this.silent.stop();
    }

    @Test
    void countersFromASilentDeviceAreQueryableAsARateInVictoriaMetrics() throws Exception {
        long octets = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            octets += 2_000;   // 1000 bytes/s at a 2 s interval
            this.silent.setCounter(1, 6, octets);
            Thread.sleep(2_000);
        }
        forceFlush();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            final JsonNode result = query("rate(ifHCInOctets{exporter=\"silent-switch\",ifIndex=\"1\"}[10s])");
            assertThat(result.path("data").path("result")).isNotEmpty();
            final double rate = result.path("data").path("result").get(0).path("value").get(1).asDouble();
            assertThat(rate).isBetween(900d, 1_100d);
        });
        final JsonNode info = query("riptide_interface_info{exporter=\"silent-switch\",ifIndex=\"1\"}");
        assertThat(info.path("data").path("result").get(0).path("metric").path("ifAlias").asText())
                .isEqualTo("My ethernet interface");
    }

    private void forceFlush() throws Exception {
        this.http.send(HttpRequest.newBuilder(URI.create(vmUrl("/internal/force_flush"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode query(final String promql) throws Exception {
        final String url = vmUrl("/api/v1/query?query=" + java.net.URLEncoder.encode(promql, java.nio.charset.StandardCharsets.UTF_8));
        final HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body());
    }

    private static String vmUrl(final String path) {
        return "http://" + VM.getHost() + ":" + VM.getMappedPort(8428) + path;
    }
}
```

The flow-exporter half of the spec's IT scenario is covered by the unit test in Task 7 (same code path, only the registration trigger differs); the IT proves the silent device, the protocol and the PromQL rate. If a second agent range on the same address is needed, run the exporter agent on a second loopback address (`127.0.0.2`, which Linux and macOS accept) instead of a second port.

- [ ] **Step 3: Run the IT**

Run: `mvn -q verify -Pe2e -Dit.test=SnmpMetricsIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: `BUILD SUCCESS` and `Tests run: 1, Failures: 0` from failsafe. Confirm the failsafe line is present; a run without a `Tests run:` line tested nothing.

- [ ] **Step 4: Commit**

```bash
git add .github/e2e-images/victoriametrics.Dockerfile src/test/java/org/riptide/e2e/ContainerImages.java src/test/java/org/riptide/snmp/SnmpMetricsIT.java .github/workflows/build.yml
git commit -s -m "test(snmp): end-to-end counters into VictoriaMetrics"
```

---

### Task 10: Permit-bounded walks and a bulkhead for suspect endpoints

This task replaces the fixed walker thread pool with a permit counter over the asynchronous snmp4j callback, and gives endpoints with consecutive failures their own smaller budget. It is the concurrency model the design discussion settled on; it is last so it can be deferred to after the benchmark without blocking the rest.

**Files:**
- Modify: `src/main/java/org/riptide/snmp/SnmpUtils.java` (`walkRaw` gains an async variant returning `CompletableFuture<RawTable>`)
- Modify: `src/main/java/org/riptide/snmp/SnmpService.java`, `DefaultSnmpService.java` (`collectAsync`, `walkInterfacesAsync`)
- Modify: `src/main/java/org/riptide/snmp/InterfaceSnapshotPoller.java` (`walkers` pool replaced by two `Semaphore`s, `tick` submits without a thread)
- Modify: `src/main/java/org/riptide/snmp/SnmpPollConfig.java` (`suspectPoolWidth`, default 8)
- Test: `src/test/java/org/riptide/snmp/InterfaceSnapshotPollerTest.java`, `WalkBoundsTest.java`

**Interfaces:**
- Produces:
  - `SnmpService.collectAsync(SnmpEndpoint, CollectionDefinition, Duration) -> CompletableFuture<CollectedTable>`; the synchronous `collect` becomes `collectAsync(...).join()`.
  - `SnmpPollConfig.suspectPoolWidth` (int, default 8), consumer: the poller's suspect semaphore; proving test below.
  - Poller meters: `snmp.poller.inFlight` (gauge), `snmp.poller.suspectInFlight` (gauge), `snmp.poller.deferred` (meter: due walks that found no permit).

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void suspectEndpointsDrawFromTheirOwnBudgetSoHealthyOnesAreNeverStarved() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);
        final var config = config();
        config.setPoolWidth(2);
        config.setSuspectPoolWidth(1);
        final var poller = poller(snmp, config, new RecordingSink());
        // three endpoints that have already failed once each, and one healthy endpoint
        for (final String ip : List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")) {
            final var ep = endpoint(ip);
            snmp.failing.add(ep);
            poller.trackAndResolve(ep, 1);
        }
        final var healthy = endpoint("10.0.0.9");
        poller.trackAndResolve(healthy, 1);
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        // all four first walks ran (nobody was a suspect yet); now the three are suspects
        advanceMs(config.getDeadEndpointBaseMs() + 1);
        snmp.block = true;
        poller.tick(this.clock.get());
        snmp.entered.await(2, TimeUnit.SECONDS);

        assertThat(this.metrics.gauge("snmp.poller.suspectInFlight").getValue()).isEqualTo(1);
        assertThat(this.metrics.meter("snmp.poller.deferred").getCount()).isEqualTo(2);
        snmp.release.countDown();
    }

    @Test
    void aDueWalkWithoutAPermitStaysDueAndRunsOnTheNextTick() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.block = true;
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);
        final var config = config();
        config.setPoolWidth(1);
        final var poller = poller(snmp, config, new RecordingSink());
        poller.trackAndResolve(endpoint("10.0.0.1"), 1);
        poller.trackAndResolve(endpoint("10.0.0.2"), 1);
        poller.tick(this.clock.get());
        snmp.entered.await(2, TimeUnit.SECONDS);
        assertThat(this.metrics.gauge("snmp.poller.inFlight").getValue()).isEqualTo(1);
        snmp.release.countDown();
        awaitWalks(poller, snmp, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
    }
```

`FakeSnmp` gains a `Set<SnmpEndpoint> failing` (those return a failed table) and implements the async methods by running the synchronous fake on a small executor so the blocking latches keep working.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=InterfaceSnapshotPollerTest -DfailIfNoTests=false`
Expected: compilation failure on `setSuspectPoolWidth`.

- [ ] **Step 3: Async walk in `SnmpUtils`**

`WalkCollector` already completes on the snmp4j callback thread. Add:

```java
    static CompletableFuture<RawTable> walkRawAsync(final Snmp snmp, final Target<?> target, final SnmpEndpoint endpoint,
                                                    final OID[] columns, final int maxRowsPerPdu,
                                                    final long deadlineNanos, final ScheduledExecutorService timer) {
        final CompletableFuture<RawTable> future = new CompletableFuture<>();
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        tableUtils.setRowLimit(MAX_TABLE_ROWS + 1);
        tableUtils.setMaxNumRowsPerPDU(maxRowsPerPdu);
        tableUtils.setMaxNumColumnsPerPDU(columns.length);
        final WalkCollector collector = new WalkCollector(MAX_TABLE_ROWS, () -> future.complete(toRawTable(collector, endpoint)));
        final ScheduledFuture<?> deadline = timer.schedule(() -> {
            if (collector.abandon()) {
                future.complete(new RawTable(Map.of(), WalkOutcome.ABANDONED));
            }
        }, deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        future.whenComplete((r, t) -> deadline.cancel(false));
        tableUtils.getTable(target, columns, collector, null, null, null);
        return future;
    }
```

`WalkCollector` gets an optional `Runnable onDone` invoked once when the latch counts down, and `abandon()` returns whether it was the first to finish. `toRawTable` is the existing event loop extracted into a static method. The `timer` is a single daemon `ScheduledExecutorService` owned by `DefaultSnmpService`, named `snmp-walk-deadline`. Keep the synchronous `walkRaw` as `walkRawAsync(...).join()` guarded by the same deadline, so `WalkBoundsTest` still holds.

- [ ] **Step 4: Permits in the poller**

Replace `walkers` with:

```java
    private final Semaphore permits;          // config.getPoolWidth()
    private final Semaphore suspectPermits;   // config.getSuspectPoolWidth()
```

In `tick`, where a walk is submitted:

```java
            final boolean suspect = registration.consecutiveFailures.get() > 0;
            final Semaphore budget = suspect ? this.suspectPermits : this.permits;
            if (!budget.tryAcquire()) {
                this.deferred.mark();
                registration.walkInFlight.set(false);
                continue;   // stays due; the next tick tries again
            }
            walkAsync(registration).whenComplete((ignored, t) -> {
                budget.release();
                registration.walkInFlight.set(false);
            });
```

`walkAsync` is `walk` rewritten around `collectAsync` / `walkInterfacesAsync` with the result handling in a `thenAccept`. The `finally` block that cleared `walkInFlight` moves into the `whenComplete` above. `stop()` no longer shuts down a pool; it lets in-flight futures finish within the 3-second wait or abandons them. Gauges `inFlight` and `suspectInFlight` read `poolWidth - permits.availablePermits()` and the same for suspects. Validate `suspectPoolWidth` positive in the constructor with the key name `riptide.snmp.poll.suspect-pool-width`.

- [ ] **Step 5: Run the poller and walk tests**

Run: `mvn -q test -Dtest='InterfaceSnapshotPollerTest,WalkBoundsTest,SnmpTest,SnmpCollectTest,SnmpEnricherTest' -DfailIfNoTests=false`
Expected: pass.

- [ ] **Step 6: Docs and commit**

Add `suspect-pool-width` to the `riptide.snmp.poll.*` table in `docs/docs/reference/agent-configuration.md` and the three new series to `reference/metrics.md`, then:

```bash
git add src/main/java/org/riptide/snmp src/test/java/org/riptide/snmp docs/docs/reference
git commit -s -m "refactor(snmp): permit-bounded asynchronous walks with a suspect bulkhead"
```

---

## Before opening the PR

1. Run `make jar` and confirm `BUILD SUCCESS`; quote the surefire total. This covers Checkstyle, Error Prone, SpotBugs, JaCoCo and every unit test, and no IT.
2. Run `make e2e` (or the targeted `-Dit.test=SnmpMetricsIT` command from Task 9) and quote the failsafe `Tests run:` line.
3. Run `/code-review medium` on the branch, per the standing rule for this repo.
4. The PR description states: the two spec deviations from Global Constraints, that the openspec sibling was edited locally, the new runtime dependency and why it is pure Java, and that the scale benchmark from the spec has not run yet. Do not propose a version bump; that is Ronny's call.
