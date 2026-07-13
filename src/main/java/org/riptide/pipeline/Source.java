/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import java.net.InetAddress;
import java.util.Objects;

public class Source {
    private final String location;

    private final ExporterIdentity identity;

    public Source(final String location, final ExporterIdentity identity) {
        this.location = Objects.requireNonNull(location);
        this.identity = Objects.requireNonNull(identity);
    }

    /** Convenience for protocols (and tests) without an observation-domain concept. */
    public Source(final String location, final InetAddress exporterAddr) {
        this(location, new ExporterIdentity.NetflowIpfix(exporterAddr, 0));
    }

    public String getLocation() {
        return this.location;
    }

    public ExporterIdentity identity() {
        return this.identity;
    }

    /**
     * Derived from the identity's device address. Kept as a bean getter on purpose:
     * {@code EnrichedFlow.FlowMapper} maps {@code Source} properties by name, so this
     * accessor is what populates the persisted {@code exporterAddr} column.
     */
    public InetAddress getExporterAddr() {
        return this.identity.deviceAddress();
    }
}
