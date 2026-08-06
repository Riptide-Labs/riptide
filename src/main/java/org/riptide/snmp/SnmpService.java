/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.util.Map;
import java.util.Optional;

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
     */
    InterfaceTable walkInterfaces(SnmpEndpoint snmpEndpoint);

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
}
