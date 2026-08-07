/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.util.Optional;

/**
 * Where the enrichment ladder's live-SNMP rung gets its data.
 *
 * <p>Deliberately narrow, and deliberately not {@link SnmpService}: an implementation MUST NOT
 * issue SNMP, open a session, or block. The flow path calls this per flow and per direction, so
 * anything that can wait on a network round trip does not belong behind it. The production
 * implementation, {@link InterfaceSnapshotPoller}, reads a snapshot a background walk already
 * produced.
 */
@FunctionalInterface
public interface InterfaceSource {

    /**
     * Records that {@code endpoint} is sending flows, then resolves one interface.
     *
     * <p>The tracking half is load-bearing, not a side effect worth optimising away. An
     * implementation may use these calls both to decide an exporter is worth polling at all and to
     * decide it is still alive, so a caller that skips this for an ifIndex it believes it already
     * knows withholds liveness for the whole exporter. {@link InterfaceSnapshotPoller} deregisters
     * after {@code refresh-interval-ms × deregister-after} without a call and drops the snapshot
     * with the registration, so the exporter's next flow starts from a cold warmup window. Call it
     * for every flow and direction carrying a usable ifIndex, and use the answer or don't.
     *
     * <p>Empty means "not known right now" rather than "does not exist": during the warmup window
     * between an exporter's first flow and its first completed walk, every ifIndex resolves empty.
     */
    Optional<IfInfo> trackAndResolve(SnmpEndpoint endpoint, int ifIndex);
}
