package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

@Data
@ConfigurationProperties(prefix = "riptide.snmp.config")
public class SnmpConfiguration {
    public List<SnmpDefinition> definitions;

    public Optional<SnmpEndpoint> lookup(final InetAddress host) {
        return lookup(host.getHostAddress());
    }

    public Optional<SnmpEndpoint> lookup(final String host) {
        final IPAddressString ipAddressString = new IPAddressString(host);
        for (final SnmpDefinition snmpDefinition : this.definitions) {
            if (snmpDefinition.getSubnetAddress().contains(ipAddressString)) {
                return Optional.of(snmpDefinition.createEndpoint(ipAddressString));
            }
        }
        return Optional.empty();
    }
}
