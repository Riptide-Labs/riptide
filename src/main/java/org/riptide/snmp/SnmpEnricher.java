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
import org.springframework.core.annotation.Order;
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
@Order(org.riptide.pipeline.EnricherOrder.SNMP)
@RequiredArgsConstructor
public class SnmpEnricher implements Enricher {

    @NonNull
    private final SnmpService snmpService;

    @NonNull
    private final NodeRegistry nodeRegistry;

    @NonNull
    private final ExporterInterfaceTable exporterInterfaceTable;

    /**
     * Resolves inline and returns an already-completed future.
     *
     * <p>There was an offload here — originally {@code supplyAsync} onto the common ForkJoinPool,
     * then a dedicated pool — on the reasoning that a caching-layer miss can perform a synchronous
     * SNMP walk and that must not run on a parser worker. That reasoning does not survive contact
     * with the caller: {@link org.riptide.pipeline.Pipeline} joins this future, so the worker blocks
     * for the walk either way. The offload bought a park pair per batch and capped SNMP concurrency
     * at the pool size for every parser worker — strictly worse than doing the work in place.
     *
     * <p>Consequence to be aware of rather than hidden: a first-touch cache miss blocks the calling
     * parser worker for the walk timeout, and with a bounded dispatch queue upstream that shows up as
     * queue depth and, if sustained, counted drops. That is the honest shape of a synchronous
     * enrichment ladder. Making it genuinely asynchronous means not joining in {@code Pipeline}
     * (issue #384), not moving the block somewhere else.
     */
    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        // exporter-pushed option data enriches even without a configured node —
        // it is keyed by exporter identity, not by node
        final Optional<Node> node = this.nodeRegistry.lookup(source.identity());
        if (node.isEmpty() && this.exporterInterfaceTable.isEmpty()) {
            return CompletableFuture.completedFuture(null); // nothing could contribute
        }

        enrichInline(source, flows, node, node.flatMap(Node::snmpEndpoint));
        return CompletableFuture.completedFuture(null);
    }

    private void enrichInline(final Source source,
                              final List<EnrichedFlow> flows,
                              final Optional<Node> node,
                              final Optional<SnmpEndpoint> snmpEndpoint) {
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
    }

    private void apply(final Optional<Node> node, final Optional<SnmpEndpoint> snmpEndpoint, final Source source,
                       final Integer ifIndex, final Consumer<IfInfo> setter) {
        // ifIndex 0 is the NetFlow/IPFIX "unknown interface" marker (valid indexes start at 1,
        // RFC 2863) — exporters tagging a single direction emit it on every flow, and no rung
        // of the ladder can ever resolve it
        if (ifIndex == null || ifIndex <= 0) {
            return;
        }
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
