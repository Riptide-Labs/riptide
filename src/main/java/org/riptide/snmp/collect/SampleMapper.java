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
