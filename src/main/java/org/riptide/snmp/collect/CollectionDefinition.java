/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.snmp4j.smi.OID;

import java.util.List;

/**
 * What one walk reads and how each row becomes samples. A definition loaded from YAML later is
 * the same record from a different loader; only the built-in one exists in this phase.
 *
 * @param maxRowsPerPdu rows per GETBULK response. Seventeen columns at snmp4j's default of ten
 *                      rows is about 170 varbinds per PDU, which exceeds what many agents will
 *                      answer in one datagram; five keeps a PDU near 1.3 KB.
 */
public record CollectionDefinition(String name, String indexLabel, List<Column> columns,
                                   int maxRowsPerPdu) {

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
}
