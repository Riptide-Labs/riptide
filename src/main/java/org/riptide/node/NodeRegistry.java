/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The node model: matches exporter identities to configured {@link NodeDefinition}s.
 * Nodes are configured as a name-keyed map ({@code riptide.nodes.<name>.…}, same idiom
 * as receivers); the key is the node's identity in logs and error messages.
 */
@Data
@ConfigurationProperties(prefix = "riptide")
public class NodeRegistry {

    private Map<String, NodeDefinition> nodes = new HashMap<>();

    /**
     * Matching is order-free: nodes pinned to the flow's observation domain beat
     * wildcard nodes; among the remaining candidates the longest subnet prefix wins.
     * True ties are rejected at startup by {@link #validate()}.
     */
    public Optional<Node> lookup(final ExporterIdentity identity) {
        // instanceof instead of an exhaustive switch pattern only because checkstyle 9.3
        // cannot parse switch record patterns; new ExporterIdentity variants must be
        // handled here.
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            return lookup(netflowIpfix.source(), netflowIpfix.observationDomain());
        }
        if (identity instanceof ExporterIdentity.Sflow sflow) {
            // agent address from the payload, sub-agent ID pins via the same
            // observation-domain node key
            return lookup(sflow.agentAddress(), sflow.subAgentId());
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }

    private Optional<Node> lookup(final InetAddress address, final long domain) {
        final IPAddressString ipAddressString = new IPAddressString(address.getHostAddress());
        final List<Map.Entry<String, NodeDefinition>> subnetMatches = this.nodes.entrySet().stream()
                .filter(node -> node.getValue().getSubnetAddress().contains(ipAddressString))
                .toList();

        final List<Map.Entry<String, NodeDefinition>> pinned = subnetMatches.stream()
                .filter(node -> node.getValue().getObservationDomain() != null
                        && node.getValue().getObservationDomain() == domain)
                .toList();
        final List<Map.Entry<String, NodeDefinition>> pool = !pinned.isEmpty()
                ? pinned
                : subnetMatches.stream().filter(node -> node.getValue().getObservationDomain() == null).toList();

        return pool.stream()
                .max(Comparator.comparingInt(node -> prefixLength(node.getValue().getSubnetAddress())))
                .map(node -> new Node(node.getKey(), node.getValue(), ipAddressString));
    }

    /**
     * Fails startup on ambiguous configuration. Equal-length CIDR prefixes are either
     * identical or disjoint, so a true tie is exactly two nodes with the same subnet and
     * the same pinning state — detection is complete, not heuristic.
     */
    @PostConstruct
    void validate() {
        final Map<String, String> seen = new HashMap<>();
        for (final Map.Entry<String, NodeDefinition> node : this.nodes.entrySet()) {
            if (node.getValue().getSubnetAddress() == null) {
                throw new IllegalStateException("Node '%s' has no subnet-address — every node needs one to match exporters."
                        .formatted(node.getKey()));
            }
            final String key = canonicalSubnet(node.getValue().getSubnetAddress())
                    + "@" + (node.getValue().getObservationDomain() != null ? node.getValue().getObservationDomain() : "wildcard");
            final String other = seen.putIfAbsent(key, node.getKey());
            if (other != null) {
                throw new IllegalStateException(("Ambiguous node configuration: '%s' and '%s' declare the same subnet "
                        + "with the same observation-domain pinning — matching between them would be arbitrary. "
                        + "Merge them or distinguish them by subnet or observation-domain.")
                        .formatted(other, node.getKey()));
            }
        }
    }

    private static int prefixLength(final IPAddressString subnet) {
        final Integer prefix = subnet.getNetworkPrefixLength();
        if (prefix != null) {
            return prefix;
        }
        // a bare host address is the most specific match possible
        return subnet.getAddress() != null ? subnet.getAddress().getBitCount() : 0;
    }

    private static String canonicalSubnet(final IPAddressString subnet) {
        return subnet.getAddress() != null ? subnet.getAddress().toPrefixBlock().toString() : String.valueOf(subnet);
    }
}
