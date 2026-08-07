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
    private final InterfaceSource interfaceSource;

    @NonNull
    private final NodeRegistry nodeRegistry;

    @NonNull
    private final ExporterInterfaceTable exporterInterfaceTable;

    /**
     * Resolves inline and returns an already-completed future.
     *
     * <p>Inline is now correct rather than merely honest. This used to perform a synchronous SNMP
     * walk on a first-touch cache miss, so a parser worker blocked for the walk timeout and a
     * sustained miss rate showed up as dispatch queue depth and counted drops. Interface data now
     * comes from {@link InterfaceSnapshotPoller}, which reads an already-walked snapshot, so there
     * is no SNMP call left to offload and nothing to block on.
     *
     * <p>The remaining consequence is a warmup window, not a stall: between an exporter's first
     * flow and its first completed walk there is no snapshot, so those flows carry no
     * SNMP-derived interface fields. Static pins and exporter-pushed option data still apply, so
     * enrichment degrades rather than fails.
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
        // reads the polled snapshot; registers the exporter on its first flow but never walks.
        // Called even when the pins above already cover every field: the call is what keeps the
        // exporter in the poll set, so skipping it would cost the interfaces that are not pinned.
        final IfInfo live = snmpEndpoint
                .flatMap(endpoint -> this.interfaceSource.trackAndResolve(endpoint, ifIndex))
                .orElse(null);
        final IfInfo merged = IfInfo.merge(pinned, IfInfo.optionsThenSnmp(options, live));
        if (merged != null) {
            setter.accept(merged);
        }
    }
}
