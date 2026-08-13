/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Today's implementation of the semantics seam, backed by {@link NodeRegistry}.
 * Declaration order is preserved via {@link LinkedHashMap} because validation
 * reports conflicting entries in first-seen order.
 */
final class NodeRegistryMatchSemantics implements ExporterMatchSemantics {

    @Override
    public Matcher build(final List<Entry> entries) {
        final Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        for (final Entry entry : entries) {
            final NodeDefinition definition = new NodeDefinition();
            if (entry.subnet() != null) {
                definition.setSubnetAddress(new IPAddressString(entry.subnet()));
            }
            definition.setObservationDomain(entry.observationDomainPin());
            if (nodes.put(entry.name(), definition) != null) {
                throw new IllegalStateException("Duplicate entry name: " + entry.name());
            }
        }
        final NodeRegistry registry = new NodeRegistry();
        registry.setNodes(nodes);
        registry.validate();
        return identity -> registry.lookup(identity).map(Node::label);
    }
}
