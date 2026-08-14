/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.pipeline.ExporterIdentity;

import java.util.Optional;

/**
 * Read access to the agents tree, captured from exactly one {@link InventorySnapshot}
 * instance: consumers capture a view once per unit of work and it never re-reads the
 * published snapshot reference (AD-3). Keyed on the device address of the identity
 * (the payload agent address for sFlow, the UDP source otherwise; AD-11).
 */
public interface AgentView {

    Optional<AgentEntry> match(ExporterIdentity identity);
}
