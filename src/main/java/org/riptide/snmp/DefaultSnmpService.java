/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.riptide.secrets.SecretResolvers;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultSnmpService implements SnmpService {

    @NonNull
    private final SecretResolvers secretResolvers;

    @Override
    public Optional<IfInfo> getIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        return lookupIfInfo(snmpEndpoint, ifIndex).ifInfo();
    }

    @Override
    public IfInfoLookup lookupIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        try {
            final var walk = SnmpUtils.getIfInfoMap(snmpEndpoint, this.secretResolvers);
            return new IfInfoLookup(Optional.ofNullable(walk.rows().get(ifIndex)),
                    walk.outcome() == SnmpUtils.WalkOutcome.TIMEOUT);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: an unresolvable secret reference must degrade to an
            // unenriched flow, never fail the pipeline and drop the batch.
            log.warn("Error fetching value from {} at index {}: {}", snmpEndpoint, ifIndex, e.getMessage());
            return new IfInfoLookup(Optional.empty(), false);
        }
    }
}
