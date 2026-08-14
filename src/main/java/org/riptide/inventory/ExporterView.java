/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.pipeline.ExporterIdentity;

import java.util.Optional;

/**
 * Read access to the exporters tree, captured from exactly one
 * {@link InventorySnapshot} instance: consumers capture a view once per unit of work
 * and it never re-reads the published snapshot reference (AD-3). Keyed on the device
 * address with the identity's observation domain against entry pins (AD-11).
 */
public interface ExporterView {

    Optional<ExporterEntry> match(ExporterIdentity identity);
}
