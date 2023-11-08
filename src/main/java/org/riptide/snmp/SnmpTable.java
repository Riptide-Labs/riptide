package org.riptide.snmp;

import org.snmp4j.smi.OID;

interface SnmpTable {
    int getColumn();
    OID getOid();
}
