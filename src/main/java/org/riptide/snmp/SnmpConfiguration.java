package org.riptide.snmp;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import inet.ipaddr.IPAddressString;
import lombok.Data;

@ConfigurationProperties(prefix = "riptide.snmp.config")
@Data
public class SnmpConfiguration {
    public List<SnmpDefinition> definitions;

    public Optional<SnmpDefinition.SnmpEndpoint> lookup(final InetAddress host) {
        return lookup(host.getHostAddress());
    }

    public Optional<SnmpDefinition.SnmpEndpoint> lookup(final String host) {
        final IPAddressString ipAddressString = new IPAddressString(host);
        for (final SnmpDefinition snmpDefinition : this.definitions) {
            if (snmpDefinition.getSubnetAddress().contains(ipAddressString)) {
                return Optional.of(snmpDefinition.createEndpoint(ipAddressString));
            }
        }

        return Optional.empty();
    }
}
