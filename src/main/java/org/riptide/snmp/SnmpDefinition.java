package org.riptide.snmp;

import java.net.InetSocketAddress;

import org.snmp4j.fluent.TargetBuilder;

import inet.ipaddr.IPAddressString;
import lombok.Data;

@Data
public class SnmpDefinition {
    private TargetBuilder.AuthProtocol authProtocol;

    private String authPassphrase;

    private TargetBuilder.PrivProtocol privProtocol;

    private String privPassphrase;

    private String community;

    private String securityName;

    private SnmpVersion snmpVersion;

    private int timeout = 500;

    private int retries = 1;

    private IPAddressString subnetAddress;

    private int port = 161;

    public SnmpEndpoint createEndpoint(final IPAddressString ipAddressString) {
        final InetSocketAddress inetSocketAddress = new InetSocketAddress(ipAddressString.getHostAddress().toInetAddress(), getPort());
        return new SnmpEndpoint(this, inetSocketAddress);
    }
}
