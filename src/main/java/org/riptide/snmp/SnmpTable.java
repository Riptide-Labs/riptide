/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.snmp4j.smi.OID;

interface SnmpTable {
    int getColumn();
    OID getOid();
}
