/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.riptide.snmp.collect.CollectionDefinition;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * One pollable agent: where to send the walk and how to authenticate it, plus the
 * cadence its polling profile asks for.
 *
 * <p>The cadence rides here rather than being looked up by the poller because the
 * poller keys its registrations by socket address and has no view of the inventory:
 * carrying it on the endpoint keeps the profile that matched a range attached to the
 * thing built from it. Both fields are {@code null} for an endpoint built the legacy
 * way, and the poller falls back to its fleet-wide settings for those.</p>
 *
 * <p>{@code collections} is never {@code null}, empty by default: equality including
 * it is what lets the poller notice, purely from {@code equals}, that a profile
 * change added or removed a collection and re-resolve the endpoint.</p>
 */
@Getter
@EqualsAndHashCode
public final class SnmpEndpoint {
    private final InetSocketAddress inetSocketAddress;
    private final SnmpDefinition snmpDefinition;
    private final Duration refreshInterval;
    private final Duration snapshotExpiry;
    private final List<CollectionDefinition> collections;

    SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress) {
        this(snmpDefinition, inetSocketAddress, null, null, List.of());
    }

    private SnmpEndpoint(final SnmpDefinition snmpDefinition, final InetSocketAddress inetSocketAddress,
                         final Duration refreshInterval, final Duration snapshotExpiry,
                         final List<CollectionDefinition> collections) {
        this.snmpDefinition = snmpDefinition;
        this.inetSocketAddress = inetSocketAddress;
        this.refreshInterval = refreshInterval;
        this.snapshotExpiry = snapshotExpiry;
        this.collections = collections;
    }

    /** The same endpoint, carrying the cadence a polling profile asks for. */
    public SnmpEndpoint withCadence(final Duration refreshInterval, final Duration snapshotExpiry) {
        return new SnmpEndpoint(this.snmpDefinition, this.inetSocketAddress, refreshInterval, snapshotExpiry,
                this.collections);
    }

    /** The same endpoint, carrying the collections a polling profile names in {@code collect}. */
    public SnmpEndpoint withCollections(final List<CollectionDefinition> collections) {
        return new SnmpEndpoint(this.snmpDefinition, this.inetSocketAddress, this.refreshInterval,
                this.snapshotExpiry, List.copyOf(collections));
    }

    @Override
    public String toString() {
        // log-friendly identity; credentials live behind SecretRefs and stay out
        return getInetSocketAddress() + " (" + this.snmpDefinition.getSnmpVersion() + ")";
    }
}
