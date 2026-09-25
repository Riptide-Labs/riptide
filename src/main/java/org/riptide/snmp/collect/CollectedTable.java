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
