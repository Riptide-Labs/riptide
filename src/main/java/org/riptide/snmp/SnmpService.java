/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

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

    record IfInfoLookup(Optional<IfInfo> ifInfo, boolean endpointTimedOut) {
    }
}
