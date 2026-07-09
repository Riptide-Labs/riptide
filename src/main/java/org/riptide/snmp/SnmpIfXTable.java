/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.snmp4j.smi.OID;
import org.springframework.stereotype.Component;

@Component
public class SnmpIfXTable implements SnmpTable {
    private static final OID TABLE_OID = new OID(".1.3.6.1.2.1.31.1.1.1");

    public int getColumn() {
        return 1;
    }

    public OID getOid() {
        return TABLE_OID;
    }
}
