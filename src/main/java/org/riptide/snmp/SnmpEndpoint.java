/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.InetSocketAddress;

@Getter
@EqualsAndHashCode
public final class SnmpEndpoint {
    private final InetSocketAddress inetSocketAddress;
    private final SnmpDefinition snmpDefinition;

    SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress) {
        this.snmpDefinition = snmpDefinition;
        this.inetSocketAddress = inetSocketAddress;
    }

    @Override
    public String toString() {
        // log-friendly identity; credentials live behind SecretRefs and stay out
        return getInetSocketAddress() + " (" + this.snmpDefinition.getSnmpVersion() + ")";
    }
}
