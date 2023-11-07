package org.riptide.snmp;

import java.io.IOException;
import java.net.UnknownHostException;

import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.smi.OctetString;
import org.springframework.stereotype.Component;

@Component
public class SnmpVersion1 implements SnmpVersion {
    @Override
    public SnmpBuilder getSnmpBuilder() throws IOException {
        return new SnmpBuilder()
                .securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility)
                .udp()
                .v1()
                .threads(2);
    }

    @Override
    public Target<?> getTarget(final Snmp snmp, final SnmpBuilder snmpBuilder, final SnmpEndpoint snmpEndpoint) throws UnknownHostException {
        return snmpBuilder
                .v1()
                .target(getTargetAddress(snmpEndpoint))
                .community(new OctetString(snmpEndpoint.getSnmpDefinition().getCommunity()))
                .timeout(snmpEndpoint.getSnmpDefinition().getTimeout())
                .retries(snmpEndpoint.getSnmpDefinition().getRetries())
                .build();
    }

}
