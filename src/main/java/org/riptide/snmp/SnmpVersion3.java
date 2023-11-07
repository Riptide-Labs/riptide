package org.riptide.snmp;

import java.io.IOException;
import java.net.UnknownHostException;

import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.smi.Address;
import org.springframework.stereotype.Component;

@Component
public class SnmpVersion3 implements SnmpVersion {
    @Override
    public SnmpBuilder getSnmpBuilder() throws IOException {
        return new SnmpBuilder()
                .securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility)
                .udp()
                .v3()
                .usm()
                .threads(2);
    }

    @Override
    public Target<?> getTarget(final Snmp snmp, final SnmpBuilder snmpBuilder, final SnmpEndpoint snmpEndpoint) throws UnknownHostException {
        final Address targetAddress = getTargetAddress(snmpEndpoint);
        final byte[] targetEngineID = snmp.discoverAuthoritativeEngineID(targetAddress, 1000);
        return snmpBuilder
                .v3()
                .target(targetAddress)
                .user(snmpEndpoint.getSnmpDefinition().getSecurityName(), targetEngineID)
                .auth(snmpEndpoint.getSnmpDefinition().getAuthProtocol()).authPassphrase(snmpEndpoint.getSnmpDefinition().getAuthPassphrase())
                .priv(snmpEndpoint.getSnmpDefinition().getPrivProtocol()).privPassphrase(snmpEndpoint.getSnmpDefinition().getPrivPassphrase())
                .done()
                .timeout(snmpEndpoint.getSnmpDefinition().getTimeout())
                .retries(snmpEndpoint.getSnmpDefinition().getRetries())
                .build();
    }
}
