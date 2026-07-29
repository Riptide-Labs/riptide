/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * Dedicated pool for the one path that can block — a caching-layer miss doing an SNMP walk.
     * Deliberately not the common ForkJoinPool: its parallelism is
     * {@code availableProcessors() - 1} (zero on a single-vCPU container, where the JDK silently
     * degrades to a thread per task), and it is shared with parallel streams and every other
     * defaulting caller in the JVM, so a walk timeout there stalls unrelated work.
     *
     * <p>Sized for wall-clock waiting rather than CPU: these threads sit in a socket read. Daemon
     * threads so a wedged walk cannot hold up JVM exit.
     */
    private volatile ExecutorService snmpExecutor = newSnmpExecutor();

    private static ExecutorService newSnmpExecutor() {
        return Executors.newFixedThreadPool(SNMP_THREADS, new ThreadFactoryBuilder()
                .setNameFormat("snmp-enricher-%d")
                .setDaemon(true)
                .build());
    }

    private static final int SNMP_THREADS = 4;

    @Override
    public void start() {
        if (this.snmpExecutor.isShutdown()) {
            this.snmpExecutor = newSnmpExecutor();
        }
    }

    @Override
    public void stop() {
        this.snmpExecutor.shutdownNow();
    }

    /**
     * Offloading is only warranted when this call could actually block, and usually it cannot.
     *
     * <p>The only blocking step is {@link org.riptide.snmp.SnmpService#getIfInfo}, and it is
     * reached solely when the matched node carries an SNMP endpoint — behind the caching layer,
     * where a first-touch miss performs a synchronous walk. Everything else here is map lookups:
     * the node registry, the exporter option table, and the node's pinned interfaces.
     *
     * <p>Wrapping the lot in {@code supplyAsync} therefore bought a common-ForkJoinPool round trip
     * and, because {@code Pipeline} joins the future, a park per call — for map lookups. Measured
     * at riptide's ~61k rows/s ceiling that was ~52% of all worker parks, with the common pool
     * itself 100% idle in {@code ForkJoinPool.awaitWork}: the round trip was not doing work, it was
     * only adding latency the caller then blocked on. The pipeline was context-switch-bound at 2.4
     * of 4 cores.
     *
     * <p>So: resolve inline and return an already-completed future unless an SNMP endpoint is in
     * play, in which case offload to a dedicated executor — never the common pool, whose
     * parallelism is {@code availableProcessors() - 1} and shared with every other defaulting
     * caller in the JVM.
     */
    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        // exporter-pushed option data enriches even without a configured node —
        // it is keyed by exporter identity, not by node
        final Optional<Node> node = this.nodeRegistry.lookup(source.identity());
        if (node.isEmpty() && this.exporterInterfaceTable.isEmpty()) {
            return CompletableFuture.completedFuture(null); // nothing could contribute
        }

        final Optional<SnmpEndpoint> snmpEndpoint = node.flatMap(Node::snmpEndpoint);
        if (snmpEndpoint.isEmpty()) {
            // No endpoint means getIfInfo() is unreachable, so nothing here can block: pinned
            // interfaces and option-table entries are both in-memory lookups.
            enrichInline(source, flows, node, snmpEndpoint);
            return CompletableFuture.completedFuture(null);
        }

        // A cache miss can block for the SNMP walk timeout, which must not happen on a parser
        // worker: it would stall the batch behind it and, through the handoff, the listener too.
        return CompletableFuture.runAsync(() -> enrichInline(source, flows, node, snmpEndpoint),
                this.snmpExecutor);
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
