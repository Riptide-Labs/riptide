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
}
