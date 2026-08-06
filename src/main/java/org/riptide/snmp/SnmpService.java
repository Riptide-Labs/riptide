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
     * Like {@link #getIfInfo}, additionally reporting whether the endpoint failed to answer at
     * all (walk timeout) — the caching layer uses this to back off per endpoint, not per
     * ifIndex.
     */
    default IfInfoLookup lookupIfInfo(SnmpEndpoint snmpEndpoint, int ifIndex) {
        return new IfInfoLookup(getIfInfo(snmpEndpoint, ifIndex), false);
    }

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

    record IfInfoLookup(Optional<IfInfo> ifInfo, boolean endpointTimedOut) {
    }

    /**
     * One exporter's interface table as a single walk produced it. An empty {@code rows} with
     * {@code endpointTimedOut} false means the agent answered but carried nothing usable;
     * with it true, the agent did not answer at all and a second walk would not help either.
     */
    record InterfaceTable(Map<Integer, IfInfo> rows, boolean endpointTimedOut) {
    }
}
