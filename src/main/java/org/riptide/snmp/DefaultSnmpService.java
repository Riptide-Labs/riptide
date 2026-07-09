/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Service
@Slf4j
public class DefaultSnmpService implements SnmpService {
    @Override
    public Optional<String> getIfName(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        try {
            final var value = SnmpUtils.getSnmpInterfaceMap(snmpEndpoint).get(ifIndex);
            return Optional.ofNullable(value);
        } catch (IOException e) {
            log.warn("Error fetching value from {} at index {}", snmpEndpoint, ifIndex);
            return Optional.empty();
        }
    }
}


