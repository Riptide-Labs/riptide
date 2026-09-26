/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.riptide.snmp.collect.CollectedTable;
import org.riptide.snmp.collect.CollectionDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface SnmpService {
    Optional<IfInfo> getIfInfo(SnmpEndpoint snmpEndpoint, int ifIndex);

    /**
     * Walks the endpoint's whole interface table and returns every row.
     *
     * <p>The per-ifIndex methods above are built on this one and throw away everything except
     * the row they were asked for. That discard is the waste this interface exists to expose:
     * one walk already contains every interface the exporter has, so resolving N ifIndexes
     * through {@link #getIfInfo} costs N walks where one would do.
     *
     * <p>Callers that need more than a single interface should walk once and keep the table.
     *
     * <p>Blocks the calling thread for the whole walk. The poller uses {@link #walkInterfacesAsync}.
     */
    default InterfaceTable walkInterfaces(final SnmpEndpoint snmpEndpoint) {
        return walkInterfacesAsync(snmpEndpoint).join();
    }

    /**
     * {@link #walkInterfaces} without a waiting thread: the future completes when the walk does,
     * on whichever thread finished it.
     */
    CompletableFuture<InterfaceTable> walkInterfacesAsync(SnmpEndpoint snmpEndpoint);

    /**
     * One exporter's interface table as a single walk produced it.
     *
     * <p>{@code walkFailed} covers every outcome that did not yield a usable table: a timeout, an
     * error PDU, or an exception the SNMP layer degraded. It deliberately does not distinguish
     * them, because callers treat them alike — none is worth retrying immediately. Reserve
     * per-outcome detail for the meters, which do separate them.
     */
    record InterfaceTable(Map<Integer, IfInfo> rows, boolean walkFailed) {
    }

    /**
     * Walks every column of {@code definition} and returns every row. Unlike
     * {@link #walkInterfaces} there is no ifTable fallback for a missing ifXTable — but "no
     * fallback" does not mean "always failed". On v2c/v3, a device without ifXTable answers a
     * clean, empty GETBULK for it (see {@code shouldFallback}'s javadoc): {@code walkFailed} is
     * {@code false}, the ifTable-only columns (ifOperStatus, the error/discard counters) are
     * still populated, and the ifXTable-only columns — including ifName and every HC octet
     * counter — are simply absent from every row. Only v1 fails the whole collect here, because
     * v1 answers a missing ifXTable with a noSuchName error PDU. This is the spec's "no 32-bit
     * fallback": rather than substituting ifTable's 32-bit octet counters, collect omits the
     * octet series entirely.
     *
     * <p>Blocks the calling thread for the whole collect. The poller uses {@link #collectAsync}.
     */
    default CollectedTable collect(final SnmpEndpoint snmpEndpoint, final CollectionDefinition definition,
                                   final Duration budget) {
        return collectAsync(snmpEndpoint, definition, budget).join();
    }

    /**
     * {@link #collect} without a waiting thread: the future completes when the last table walk
     * does, on whichever thread finished it.
     */
    CompletableFuture<CollectedTable> collectAsync(SnmpEndpoint snmpEndpoint, CollectionDefinition definition,
                                                   Duration budget);
}
