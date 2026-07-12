/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.node.Node;
import org.riptide.node.NodeRegistry;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Resolves flow interface indexes against the matched node: the static interface
 * mapping pins per field, live SNMP fills the rest (enrichment-ladder semantics).
 * A node without an SNMP block enriches purely statically.
 */
@Component
@RequiredArgsConstructor
public class SnmpEnricher implements Enricher {

    @NonNull
    private final SnmpService snmpService;

    @NonNull
    private final NodeRegistry nodeRegistry;

    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        return CompletableFuture.supplyAsync(() -> {
            this.nodeRegistry.lookup(source.identity()).ifPresent(node -> {
                final Optional<SnmpEndpoint> snmpEndpoint = node.snmpEndpoint();
                for (final EnrichedFlow flow : flows) {
                    apply(node, snmpEndpoint, flow.getInputSnmp(), ifInfo -> {
                        flow.setInputSnmpIfName(ifInfo.name());
                        flow.setInputSnmpIfAlias(ifInfo.alias());
                        flow.setInputSnmpIfSpeed(ifInfo.highSpeed());
                    });
                    apply(node, snmpEndpoint, flow.getOutputSnmp(), ifInfo -> {
                        flow.setOutputSnmpIfName(ifInfo.name());
                        flow.setOutputSnmpIfAlias(ifInfo.alias());
                        flow.setOutputSnmpIfSpeed(ifInfo.highSpeed());
                    });
                }
            });

            return null;
        });
    }

    private void apply(final Node node, final Optional<SnmpEndpoint> snmpEndpoint, final int ifIndex,
                       final Consumer<IfInfo> setter) {
        final IfInfo pinned = node.definition().getInterfaces().get(ifIndex);
        final IfInfo live = snmpEndpoint
                .flatMap(endpoint -> this.snmpService.getIfInfo(endpoint, ifIndex))
                .orElse(null);
        final IfInfo merged = IfInfo.merge(pinned, live);
        if (merged != null) {
            setter.accept(merged);
        }
    }
}
