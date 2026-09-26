/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the exporters tree, captured from exactly one
 * {@link InventorySnapshot} instance: consumers capture a view once per unit of work
 * and it never re-reads the published snapshot reference (AD-3). Keyed on the device
 * address with the identity's observation domain against entry pins (AD-11).
 */
public interface ExporterView {

    Optional<ExporterEntry> match(ExporterIdentity identity);

    /**
     * Every entry declaring {@code poll: always}, sorted by name. The SNMP poller registers
     * these at boot and on every reload, without waiting for a flow.
     */
    List<ExporterEntry> alwaysPolled();

    /**
     * The {@code poll: always} entry at exactly this address, whatever observation domain it is
     * pinned to. A polled sample carries no domain, so {@link #match} cannot find a pinned entry
     * for it; this can, because {@code poll: always} entries are host addresses.
     */
    Optional<ExporterEntry> alwaysPolledAt(InetAddress address);
}
