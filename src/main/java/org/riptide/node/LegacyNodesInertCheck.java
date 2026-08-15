/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Warns when {@code riptide.nodes} is populated but no longer feeds enrichment.
 *
 * <p>The tree still binds and still validates, so nothing complains, and an operator
 * reading a clean startup would reasonably conclude their configuration is live. It is
 * not: names, interface pins and SNMP credentials all come from the inventory now. This
 * is a warning rather than an error because the interim state is deliberate, the
 * converter that rewrites the tree lands in the next epic, and failing startup on a
 * config that merely became inert would strand anyone mid-migration.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyNodesInertCheck {

    private final NodeRegistry nodeRegistry;

    @PostConstruct
    void warnWhenLegacyNodesAreNoLongerRead() {
        final int nodes = this.nodeRegistry.getNodes().size();
        if (nodes == 0) {
            return;
        }
        log.warn("riptide.nodes still declares {} node(s), and none of it reaches enrichment any more: "
                + "exporter names, interface pins and SNMP credentials now come from the inventory file "
                + "(riptide.inventory.file). The declarations are inert, not broken. Convert them and "
                + "remove the tree", nodes);
    }
}
