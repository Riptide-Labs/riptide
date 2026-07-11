/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.util.Optional;

public interface SnmpService {
    Optional<IfInfo> getIfInfo(SnmpEndpoint snmpEndpoint, int ifIndex);
}
