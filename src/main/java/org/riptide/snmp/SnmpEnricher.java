/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import inet.ipaddr.IPAddressString;
import org.riptide.inventory.AgentEntry;
import org.riptide.inventory.ExporterEntry;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventorySnapshot;
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
    private final Inventory inventory;

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
        // one capture for the whole batch: both views must come from the same snapshot
        // instance, and a per-flow read would let a reload split a batch across two
        // configuration generations (AD-3)
        final InventorySnapshot snapshot = this.inventory.snapshot();
        final Optional<AgentEntry> agent = snapshot.agentView().match(source.identity());
        final ExporterEntry exporter = snapshot.exporterView().match(source.identity()).orElse(null);

        // three independent contributors now, not one: a range says how to walk, an
        // enrichment entry pins fields, and exporter-pushed option data is keyed by
        // identity and needs neither. Any one of them is reason enough to continue
        if (agent.isEmpty() && exporter == null && this.exporterInterfaceTable.isEmpty()) {
            return CompletableFuture.completedFuture(null); // nothing could contribute
        }

        // resolved once per batch, never per flow: the endpoint is constant for a source
        final Optional<SnmpEndpoint> endpoint = agent
                .flatMap(entry -> AgentEndpointFactory.endpointFor(entry, address(source)));
        enrichInline(source, flows, exporter, endpoint);
        return CompletableFuture.completedFuture(null);
    }

    private static IPAddressString address(final Source source) {
        // the flow's device address, never the range key: a /16 range must walk the
        // device that sent the flow, not the network address of the range
        return new IPAddressString(source.identity().deviceAddress().getHostAddress());
    }

    private void enrichInline(final Source source,
                              final List<EnrichedFlow> flows,
                              final ExporterEntry exporter,
                              final Optional<SnmpEndpoint> snmpEndpoint) {
        for (final EnrichedFlow flow : flows) {
            apply(exporter, snmpEndpoint, source, flow.getInputSnmp(), ifInfo -> {
                flow.setInputSnmpIfName(ifInfo.name());
                flow.setInputSnmpIfAlias(ifInfo.alias());
                flow.setInputSnmpIfSpeed(ifInfo.highSpeed());
            });
            apply(exporter, snmpEndpoint, source, flow.getOutputSnmp(), ifInfo -> {
                flow.setOutputSnmpIfName(ifInfo.name());
                flow.setOutputSnmpIfAlias(ifInfo.alias());
                flow.setOutputSnmpIfSpeed(ifInfo.highSpeed());
            });
        }
    }

    private void apply(final ExporterEntry exporter, final Optional<SnmpEndpoint> snmpEndpoint, final Source source,
                       final Integer ifIndex, final Consumer<IfInfo> setter) {
        // ifIndex 0 is the NetFlow/IPFIX "unknown interface" marker (valid indexes start at 1,
        // RFC 2863) — exporters tagging a single direction emit it on every flow, and no rung
        // of the ladder can ever resolve it
        if (ifIndex == null || ifIndex <= 0) {
            return;
        }
        final IfInfo pinned = InventoryPins.pinFor(exporter, ifIndex);
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
