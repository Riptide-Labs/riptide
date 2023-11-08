package org.riptide.snmp;

import java.util.Optional;

public interface SnmpService {
    Optional<String> getIfName(SnmpEndpoint snmpEndpoint, int ifIndex);
}
