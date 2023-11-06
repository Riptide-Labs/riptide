package org.riptide.snmp;

import java.net.InetSocketAddress;

import org.snmp4j.fluent.TargetBuilder;
import org.snmp4j.security.SecurityLevel;

import com.google.common.base.Strings;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

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

    @EqualsAndHashCode
    public static final class SnmpEndpoint {
        @Getter
        private InetSocketAddress inetSocketAddress;

        @Getter
        private SnmpDefinition snmpDefinition;

        private SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress) {
            this.snmpDefinition = snmpDefinition;
            this.inetSocketAddress = inetSocketAddress;
        }
    }

    public SnmpEndpoint createEndpoint(final IPAddressString ipAddressString) {
        final InetSocketAddress inetSocketAddress = new InetSocketAddress(ipAddressString.getHostAddress().toInetAddress(), getPort());
        return new SnmpEndpoint(this, inetSocketAddress);
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
