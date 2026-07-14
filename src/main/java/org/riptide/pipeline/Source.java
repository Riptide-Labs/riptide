/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import java.net.InetAddress;
import java.util.Objects;

public class Source {
    private final String zone;
    private final String tenant;
    private final String organisation;
    private final String system;

    private final ExporterIdentity exporter;

    public Source(final Identity identity, final ExporterIdentity exporter) {
        Objects.requireNonNull(identity);
        this.zone = identity.zone();
        this.tenant = identity.tenant();
        this.organisation = identity.organisation();
        this.system = identity.system();
        this.exporter = Objects.requireNonNull(exporter);
    }

    /** Convenience for tests without a full identity: the other dimensions default. */
    public Source(final String zone, final ExporterIdentity exporter) {
        this(new Identity("default", "default", zone, "default"), exporter);
    }

    /** Convenience for protocols (and tests) without an observation-domain concept. */
    public Source(final String zone, final InetAddress exporterAddr) {
        this(zone, new ExporterIdentity.NetflowIpfix(exporterAddr, 0));
    }

    public String getZone() {
        return this.zone;
    }

    public String getTenant() {
        return this.tenant;
    }

    public String getOrganisation() {
        return this.organisation;
    }

    public String getSystem() {
        return this.system;
    }

    public ExporterIdentity identity() {
        return this.exporter;
    }

    /**
     * Derived from the identity's device address. Kept as a bean getter on purpose:
     * {@code EnrichedFlow.FlowMapper} maps {@code Source} properties by name, so this
     * accessor is what populates the persisted {@code exporterAddr} column.
     */
    public InetAddress getExporterAddr() {
        return this.exporter.deviceAddress();
    }
}
