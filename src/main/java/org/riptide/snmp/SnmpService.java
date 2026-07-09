/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.util.Optional;

public interface SnmpService {
    Optional<String> getIfName(SnmpEndpoint snmpEndpoint, int ifIndex);
}
