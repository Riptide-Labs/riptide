/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@ConfigurationProperties(prefix = "riptide.snmp.config")
public class SnmpConfiguration {
    public List<SnmpDefinition> definitions = new ArrayList<>();

    /**
     * Selects the SNMP definition for an exporter. Definitions pinned to the flow's
     * observation domain win over wildcard definitions (no {@code observation-domain});
     * within each group the subnet match keeps the existing first-match order.
     */
    public Optional<SnmpEndpoint> lookup(final ExporterIdentity identity) {
        // instanceof instead of an exhaustive switch pattern only because checkstyle 9.3
        // cannot parse switch record patterns; new ExporterIdentity variants (sFlow, #159)
        // must be handled here.
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            final IPAddressString ipAddressString = new IPAddressString(netflowIpfix.source().getHostAddress());
            final List<SnmpDefinition> subnetMatches = this.definitions.stream()
                    .filter(definition -> definition.getSubnetAddress().contains(ipAddressString))
                    .toList();

            return subnetMatches.stream()
                    .filter(definition -> definition.getObservationDomain() != null && definition.getObservationDomain() == netflowIpfix.observationDomain())
                    .findFirst()
                    .or(() -> subnetMatches.stream()
                            .filter(definition -> definition.getObservationDomain() == null)
                            .findFirst())
                    .map(definition -> definition.createEndpoint(ipAddressString));
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }
}
