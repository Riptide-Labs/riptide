/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import jakarta.annotation.PostConstruct;
import org.riptide.inventory.PinnedPrefixMatcher;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The node model: matches exporter identities to configured {@link NodeDefinition}s.
 * Nodes are configured as a name-keyed map ({@code riptide.nodes.<name>.…}, same idiom
 * as receivers); the key is the node's identity in logs and error messages.
 *
 * <p>Bound state and serving state are separate: Spring binds into {@code nodes} at
 * boot, lookups read the volatile {@code active} matcher published by
 * {@link #validate()} — or by {@link #swap(Map)} on config hot-reload. The matcher is
 * immutable and built off the hot path, so a whole-instance swap is the whole
 * concurrency story; lookups take exactly one volatile read and never re-read.</p>
 */
@ConfigurationProperties(prefix = "riptide")
public class NodeRegistry {

    private Map<String, NodeDefinition> nodes = new HashMap<>();

    private volatile PinnedPrefixMatcher<Map.Entry<String, NodeDefinition>> active =
            PinnedPrefixMatcher.<Map.Entry<String, NodeDefinition>>builder().build();

    public Map<String, NodeDefinition> getNodes() {
        return this.nodes;
    }

    public void setNodes(final Map<String, NodeDefinition> nodes) {
        this.nodes = nodes;
    }

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
        final PinnedPrefixMatcher<Map.Entry<String, NodeDefinition>> matcher = this.active;
        final IPAddressString ipAddressString = new IPAddressString(address.getHostAddress());
        return matcher.lookup(ipAddressString, domain)
                .map(node -> new Node(node.getKey(), node.getValue(), ipAddressString));
    }

    /** Validates the boot-time bind and publishes it as the serving matcher. */
    @PostConstruct
    public void validate() {
        this.active = matcherOf(validated(this.nodes));
    }

    /** Atomically replaces the serving matcher with a validated candidate (hot-reload). */
    public void swap(final Map<String, NodeDefinition> candidate) {
        this.active = matcherOf(validated(candidate));
    }

    /**
     * Builds the immutable trie-backed matcher from a validated map. For trie-shaped
     * entries the builder's duplicate detection additionally catches spellings the
     * canonical-string check treats as distinct (a bare host vs its explicit /32),
     * naming both entries; side-pool shapes rely on {@link #validated} alone.
     */
    private static PinnedPrefixMatcher<Map.Entry<String, NodeDefinition>> matcherOf(
            final Map<String, NodeDefinition> nodes) {
        final PinnedPrefixMatcher.Builder<Map.Entry<String, NodeDefinition>> builder = PinnedPrefixMatcher.builder();
        for (final Map.Entry<String, NodeDefinition> node : nodes.entrySet()) {
            builder.add(node.getKey(), node.getValue().getSubnetAddress(),
                    node.getValue().getObservationDomain(), node);
        }
        return builder.build();
    }

    /**
     * Fails on ambiguous configuration. Equal-length CIDR prefixes are either
     * identical or disjoint, so a true tie is exactly two nodes with the same subnet and
     * the same pinning state — detection is complete, not heuristic. A trial matcher
     * build runs last, so this method throws for everything {@link #swap(Map)} could
     * throw for: the reloader pre-validates candidates with this method before
     * committing its environment swap, and that contract only holds if the real build
     * cannot fail on input this method accepted.
     *
     * @return an immutable map of the given definitions. The copy is shallow —
     *         {@link NodeDefinition} is a mutable bean — so isolation of the published
     *         matcher rests on callers passing freshly bound instances and never
     *         mutating them after publishing (both the boot bind and the hot-reload
     *         candidate bind create fresh instances)
     */
    public static Map<String, NodeDefinition> validated(final Map<String, NodeDefinition> nodes) {
        final Map<String, String> seen = new HashMap<>();
        for (final Map.Entry<String, NodeDefinition> node : nodes.entrySet()) {
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
        matcherOf(nodes);
        return Map.copyOf(nodes);
    }

    private static String canonicalSubnet(final IPAddressString subnet) {
        return subnet.getAddress() != null ? subnet.getAddress().toPrefixBlock().toString() : String.valueOf(subnet);
    }
}
