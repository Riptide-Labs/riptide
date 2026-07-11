/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The node model: matches exporter identities to configured {@link NodeDefinition}s
 * (successor of the former {@code riptide.snmp.config.definitions}).
 */
@Data
@ConfigurationProperties(prefix = "riptide")
public class NodeRegistry {

    private List<NodeDefinition> nodes = new ArrayList<>();

    /**
     * Nodes pinned to the flow's observation domain win over wildcard nodes (no
     * {@code observation-domain}); within each group the subnet match keeps
     * first-match order.
     */
    public Optional<Node> lookup(final ExporterIdentity identity) {
        // instanceof instead of an exhaustive switch pattern only because checkstyle 9.3
        // cannot parse switch record patterns; new ExporterIdentity variants (sFlow, #159)
        // must be handled here.
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            final IPAddressString ipAddressString = new IPAddressString(netflowIpfix.source().getHostAddress());
            final List<NodeDefinition> subnetMatches = this.nodes.stream()
                    .filter(node -> node.getSubnetAddress().contains(ipAddressString))
                    .toList();

            return subnetMatches.stream()
                    .filter(node -> node.getObservationDomain() != null && node.getObservationDomain() == netflowIpfix.observationDomain())
                    .findFirst()
                    .or(() -> subnetMatches.stream()
                            .filter(node -> node.getObservationDomain() == null)
                            .findFirst())
                    .map(node -> new Node(node, ipAddressString));
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }
}
