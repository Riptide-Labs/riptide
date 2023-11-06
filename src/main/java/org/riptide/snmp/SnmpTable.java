package org.riptide.snmp;

import lombok.Getter;
import org.snmp4j.smi.OID;

@Getter
enum SnmpTable {
    // There are two tables that can be used to query for human-readable names for Snmp ifIndex numbers
    IfTable(new OID(".1.3.6.1.2.1.2.2.1"), 2),
    IfXTable(new OID(".1.3.6.1.2.1.31.1.1.1"), 1);

    private final OID oid;
    private final int column;

    SnmpTable(final OID oid, final int column) {
        this.oid = oid;
        this.column = column;
    }
}
