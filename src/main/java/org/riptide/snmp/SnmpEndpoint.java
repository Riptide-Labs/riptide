/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * One pollable agent: where to send the walk and how to authenticate it, plus the
 * cadence its polling profile asks for.
 *
 * <p>The cadence rides here rather than being looked up by the poller because the
 * poller keys its registrations by socket address and has no view of the inventory:
 * carrying it on the endpoint keeps the profile that matched a range attached to the
 * thing built from it. Both fields are {@code null} for an endpoint built the legacy
 * way, and the poller falls back to its fleet-wide settings for those.</p>
 */
@Getter
@EqualsAndHashCode
public final class SnmpEndpoint {
    private final InetSocketAddress inetSocketAddress;
    private final SnmpDefinition snmpDefinition;
    private final Duration refreshInterval;
    private final Duration snapshotExpiry;

    SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress) {
        this(snmpDefinition, inetSocketAddress, null, null);
    }

    private SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress,
                         final Duration refreshInterval, final Duration snapshotExpiry) {
        this.snmpDefinition = snmpDefinition;
        this.inetSocketAddress = inetSocketAddress;
        this.refreshInterval = refreshInterval;
        this.snapshotExpiry = snapshotExpiry;
    }

    /** The same endpoint, carrying the cadence a polling profile asks for. */
    public SnmpEndpoint withCadence(final Duration refreshInterval, final Duration snapshotExpiry) {
        return new SnmpEndpoint(this.snmpDefinition, this.inetSocketAddress, refreshInterval, snapshotExpiry);
    }

    @Override
    public String toString() {
        // log-friendly identity; credentials live behind SecretRefs and stay out
        return getInetSocketAddress() + " (" + this.snmpDefinition.getSnmpVersion() + ")";
    }
}
