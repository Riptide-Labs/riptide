package org.riptide.snmp;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import inet.ipaddr.IPAddressString;
import lombok.Data;

@ConfigurationProperties(prefix = "riptide.snmp.config")
@Data
public class SnmpConfiguration {
    public List<SnmpEndpoint> endpoints;

    public Optional<SnmpEndpoint> lookup(String host) {
        final IPAddressString ipAddressString = new IPAddressString(host);

        for (final SnmpEndpoint snmpEndpoint : endpoints) {
            final IPAddressString subnetOrHost = new IPAddressString(snmpEndpoint.getIpAddress() + "/" + snmpEndpoint.getIpPrefix());
            if (subnetOrHost.contains(ipAddressString)) {
                return Optional.of(snmpEndpoint);
            }
        }

        return Optional.empty();
    }
}
