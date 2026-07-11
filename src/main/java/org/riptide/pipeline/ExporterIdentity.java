/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import java.net.InetAddress;

/**
 * Identifies the device a flow record came from. NetFlow v9 and IPFIX scope an exporter by
 * source address plus a 32-bit observation domain (RFC 7011; called Source ID in NetFlow v9),
 * so a single exporter IP can host several independent export streams. NetFlow v5 has no such
 * concept — its engine type/ID are mapped onto the domain, {@code 0} when absent.
 *
 * <p>Sealed on purpose: sFlow identifies an agent by the in-payload agent address plus
 * sub-agent ID, <em>not</em> the UDP source address. When sFlow support lands, it becomes a
 * new variant here and the compiler surfaces every place that must handle it.</p>
 */
public sealed interface ExporterIdentity {

    record NetflowIpfix(InetAddress source, long observationDomain) implements ExporterIdentity {
    }
}
