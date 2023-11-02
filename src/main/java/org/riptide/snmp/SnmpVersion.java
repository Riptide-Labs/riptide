package org.riptide.snmp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

enum SnmpVersion {
    v1() {
        @Override
        SnmpBuilder getSnmpBuilder() throws IOException {
            return new SnmpBuilder()
                    .securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility)
                    .udp()
                    .v1()
                    .threads(2);
        }

        @Override
        Target<?> getTarget(final Snmp snmp, final SnmpBuilder snmpBuilder, final SnmpEndpoint snmpEndpoint) throws UnknownHostException {
            return snmpBuilder
                    .v1()
                    .target(getTargetAddress(snmpEndpoint))
                    .community(new OctetString(snmpEndpoint.getCommunity()))
                    .timeout(snmpEndpoint.getTimeout())
                    .retries(snmpEndpoint.getRetries())
                    .build();
        }
    },

    v2c() {
        @Override
        SnmpBuilder getSnmpBuilder() throws IOException {
            return new SnmpBuilder()
                    .securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility)
                    .udp()
                    .v2c()
                    .threads(2);
        }

        @Override
        Target<?> getTarget(final Snmp snmp, final SnmpBuilder snmpBuilder, final SnmpEndpoint snmpEndpoint) throws UnknownHostException {
            return snmpBuilder
                    .v2c()
                    .target(getTargetAddress(snmpEndpoint))
                    .community(new OctetString(snmpEndpoint.getCommunity()))
                    .timeout(snmpEndpoint.getTimeout())
                    .retries(snmpEndpoint.getRetries())
                    .build();
        }
    },

    v3() {
        @Override
        SnmpBuilder getSnmpBuilder() throws IOException {
            return new SnmpBuilder()
                    .securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility)
                    .udp()
                    .v3()
                    .usm()
                    .threads(2);
        }

        @Override
        Target<?> getTarget(final Snmp snmp, final SnmpBuilder snmpBuilder, final SnmpEndpoint snmpEndpoint) throws UnknownHostException {
            final Address targetAddress = getTargetAddress(snmpEndpoint);
            final byte[] targetEngineID = snmp.discoverAuthoritativeEngineID(targetAddress, 1000);
            return snmpBuilder
                    .v3()
                    .target(targetAddress)
                    .user(snmpEndpoint.getSecurityName(), targetEngineID)
                    .auth(snmpEndpoint.getAuthProtocol()).authPassphrase(snmpEndpoint.getAuthPassphrase())
                    .priv(snmpEndpoint.getPrivProtocol()).privPassphrase(snmpEndpoint.getPrivPassphrase())
                    .done()
                    .timeout(snmpEndpoint.getTimeout())
                    .retries(snmpEndpoint.getRetries())
                    .build();

        }
    };

    abstract SnmpBuilder getSnmpBuilder() throws IOException;

    abstract Target<?> getTarget(Snmp snmp, SnmpBuilder snmpBuilder, SnmpEndpoint snmpEndpoint) throws UnknownHostException;

    Address getTargetAddress(SnmpEndpoint snmpEndpoint) throws UnknownHostException {
        return new UdpAddress(InetAddress.getByName(snmpEndpoint.getIpAddress()), snmpEndpoint.getPort());
    }
}
