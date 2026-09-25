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
