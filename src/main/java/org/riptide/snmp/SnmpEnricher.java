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
 * mapping pins per field, exporter-pushed option data and live SNMP fill the rest
 * with per-field authority (enrichment-ladder semantics). A node without an SNMP
 * block still enriches from statics and option data.
 */
@Component
@RequiredArgsConstructor
public class SnmpEnricher implements Enricher {

    @NonNull
    private final SnmpService snmpService;

    @NonNull
    private final NodeRegistry nodeRegistry;

    @NonNull
    private final ExporterInterfaceTable exporterInterfaceTable;

    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        return CompletableFuture.supplyAsync(() -> {
            // exporter-pushed option data enriches even without a configured node —
            // it is keyed by exporter identity, not by node
            final Optional<Node> node = this.nodeRegistry.lookup(source.identity());
            final Optional<SnmpEndpoint> snmpEndpoint = node.flatMap(Node::snmpEndpoint);
            for (final EnrichedFlow flow : flows) {
                apply(node, snmpEndpoint, source, flow.getInputSnmp(), ifInfo -> {
                    flow.setInputSnmpIfName(ifInfo.name());
                    flow.setInputSnmpIfAlias(ifInfo.alias());
                    flow.setInputSnmpIfSpeed(ifInfo.highSpeed());
                });
                apply(node, snmpEndpoint, source, flow.getOutputSnmp(), ifInfo -> {
                    flow.setOutputSnmpIfName(ifInfo.name());
                    flow.setOutputSnmpIfAlias(ifInfo.alias());
                    flow.setOutputSnmpIfSpeed(ifInfo.highSpeed());
                });
            }

            return null;
        });
    }

    private void apply(final Optional<Node> node, final Optional<SnmpEndpoint> snmpEndpoint, final Source source,
                       final int ifIndex, final Consumer<IfInfo> setter) {
        final IfInfo pinned = node
                .map(n -> n.definition().getInterfaces().get(ifIndex))
                .orElse(null);
        final IfInfo options = this.exporterInterfaceTable.lookup(source.identity(), ifIndex).orElse(null);
        final IfInfo live = snmpEndpoint
                .flatMap(endpoint -> this.snmpService.getIfInfo(endpoint, ifIndex))
                .orElse(null);
        final IfInfo merged = IfInfo.merge(pinned, IfInfo.optionsThenSnmp(options, live));
        if (merged != null) {
            setter.accept(merged);
        }
    }
}
