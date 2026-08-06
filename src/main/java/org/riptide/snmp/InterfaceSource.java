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
     * Resolves one interface, and may register the exporter as worth polling.
     *
     * <p>Empty means "not known right now" rather than "does not exist": during the warmup window
     * between an exporter's first flow and its first completed walk, every ifIndex resolves empty.
     */
    Optional<IfInfo> resolve(SnmpEndpoint endpoint, int ifIndex);
}
