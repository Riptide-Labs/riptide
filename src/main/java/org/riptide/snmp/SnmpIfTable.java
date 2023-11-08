package org.riptide.snmp;

import org.snmp4j.smi.OID;
import org.springframework.stereotype.Component;

@Component
public class SnmpIfTable implements SnmpTable {
    private static final OID TABLE_OID = new OID(".1.3.6.1.2.1.2.2.1");

    public int getColumn() {
        return 2;
    }

    public OID getOid() {
        return TABLE_OID;
    }

}
