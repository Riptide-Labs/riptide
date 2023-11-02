package org.riptide.snmp;

import java.net.InetSocketAddress;

import org.snmp4j.fluent.TargetBuilder;
import org.snmp4j.security.SecurityLevel;
import org.springframework.context.annotation.Configuration;

import com.google.common.base.Strings;

import lombok.Data;

@Configuration
@Data
public class SnmpEndpoint {

    private TargetBuilder.AuthProtocol authProtocol;

    private String authPassphrase;

    private TargetBuilder.PrivProtocol privProtocol;

    private String privPassphrase;

    private String community;

    private String securityName;

    private SnmpVersion snmpVersion;

    private int timeout = 500;

    private int retries = 1;

    private String ipAddress;

    private int ipPrefix = 0;

    private int port;

    public static SnmpEndpoint communityV1(final InetSocketAddress endpointInetSocketAddress, final String community) {
        final SnmpEndpoint snmpEndpoint = new SnmpEndpoint();
        snmpEndpoint.setIpAddress(endpointInetSocketAddress.getHostString());
        snmpEndpoint.setPort(endpointInetSocketAddress.getPort());
        snmpEndpoint.setSnmpVersion(SnmpVersion.v1);
        snmpEndpoint.setCommunity(community);
        snmpEndpoint.setSecurityName(null);
        snmpEndpoint.setAuthProtocol(null);
        snmpEndpoint.setAuthPassphrase(null);
        snmpEndpoint.setPrivProtocol(null);
        snmpEndpoint.setPrivPassphrase(null);
        return snmpEndpoint;
    }

    public static SnmpEndpoint communityV2c(final InetSocketAddress endpointInetSocketAddress, final String community) {
        final SnmpEndpoint snmpEndpoint = new SnmpEndpoint();
        snmpEndpoint.setIpAddress(endpointInetSocketAddress.getHostString());
        snmpEndpoint.setPort(endpointInetSocketAddress.getPort());
        snmpEndpoint.setSnmpVersion(SnmpVersion.v2c);
        snmpEndpoint.setCommunity(community);
        snmpEndpoint.setSecurityName(null);
        snmpEndpoint.setAuthProtocol(null);
        snmpEndpoint.setAuthPassphrase(null);
        snmpEndpoint.setPrivProtocol(null);
        snmpEndpoint.setPrivPassphrase(null);
        return snmpEndpoint;
    }

    public static SnmpEndpoint noAuthNoPriv(final InetSocketAddress endpointInetSocketAddress, final String securityName) {
        final SnmpEndpoint snmpEndpoint = new SnmpEndpoint();
        snmpEndpoint.setIpAddress(endpointInetSocketAddress.getHostString());
        snmpEndpoint.setPort(endpointInetSocketAddress.getPort());
        snmpEndpoint.setSnmpVersion(SnmpVersion.v3);
        snmpEndpoint.setCommunity(null);
        snmpEndpoint.setSecurityName(securityName);
        snmpEndpoint.setAuthProtocol(null);
        snmpEndpoint.setAuthPassphrase(null);
        snmpEndpoint.setPrivProtocol(null);
        snmpEndpoint.setPrivPassphrase(null);
        return snmpEndpoint;
    }

    public static SnmpEndpoint authNoPriv(final InetSocketAddress endpointInetSocketAddress, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase) {
        final SnmpEndpoint snmpEndpoint = new SnmpEndpoint();
        snmpEndpoint.setIpAddress(endpointInetSocketAddress.getHostString());
        snmpEndpoint.setPort(endpointInetSocketAddress.getPort());
        snmpEndpoint.setSnmpVersion(SnmpVersion.v3);
        snmpEndpoint.setCommunity(null);
        snmpEndpoint.setSecurityName(securityName);
        snmpEndpoint.setAuthProtocol(authProtocol);
        snmpEndpoint.setAuthPassphrase(authPassphrase);
        snmpEndpoint.setPrivProtocol(null);
        snmpEndpoint.setPrivPassphrase(null);
        return snmpEndpoint;
    }

    public static SnmpEndpoint authPriv(final InetSocketAddress endpointInetSocketAddress, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase, final TargetBuilder.PrivProtocol privProtocol, final String privPassphrase) {
        final SnmpEndpoint snmpEndpoint = new SnmpEndpoint();
        snmpEndpoint.setIpAddress(endpointInetSocketAddress.getHostString());
        snmpEndpoint.setPort(endpointInetSocketAddress.getPort());
        snmpEndpoint.setSnmpVersion(SnmpVersion.v3);
        snmpEndpoint.setCommunity(null);
        snmpEndpoint.setSecurityName(securityName);
        snmpEndpoint.setAuthProtocol(authProtocol);
        snmpEndpoint.setAuthPassphrase(authPassphrase);
        snmpEndpoint.setPrivProtocol(privProtocol);
        snmpEndpoint.setPrivPassphrase(privPassphrase);
        return snmpEndpoint;
    }

    public SecurityLevel getSecurityLevel() {
        if (snmpVersion == SnmpVersion.v3) {
            if (authProtocol != null && !Strings.isNullOrEmpty(authPassphrase)) {
                if (privProtocol != null && !Strings.isNullOrEmpty(privPassphrase)) {
                    return SecurityLevel.authPriv;
                } else {
                    return SecurityLevel.authNoPriv;
                }
            } else {
                return SecurityLevel.noAuthNoPriv;
            }
        } else {
            return SecurityLevel.undefined;
        }
    }
}


