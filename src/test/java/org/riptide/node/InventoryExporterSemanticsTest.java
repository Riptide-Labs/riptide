/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

/**
 * The characterisation contract bound to the inventory path the consumers cut over to,
 * through {@code InventoryLoader.parse} so the config-to-entry mapping is inside the
 * seam rather than assumed.
 */
class InventoryExporterSemanticsTest extends ExporterMatchSemanticsContract {

    @Override
    protected ExporterMatchSemantics semantics() {
        return new InventoryExporterMatchSemantics();
    }
}
