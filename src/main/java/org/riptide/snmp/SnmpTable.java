/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.snmp4j.smi.OID;

interface SnmpTable {
    int getColumn();
    OID getOid();
}
