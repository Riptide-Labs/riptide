package org.riptide.snmp;

import java.io.IOException;
import java.net.UnknownHostException;

import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.UdpAddress;

interface SnmpVersion {
    SnmpBuilder getSnmpBuilder() throws IOException;

    Target<?> getTarget(Snmp snmp, SnmpBuilder snmpBuilder, SnmpEndpoint snmpEndpoint) throws UnknownHostException;

    default Address getTargetAddress(SnmpEndpoint snmpEndpoint) throws UnknownHostException {
        return new UdpAddress(snmpEndpoint.getInetSocketAddress().getAddress(), snmpEndpoint.getInetSocketAddress().getPort());
    }
}
