/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.riptide.inventory.ExporterEntry;
import org.riptide.inventory.InterfacePin;

/**
 * Turns an inventory {@link InterfacePin} into the enrichment ladder's {@link IfInfo}.
 *
 * <p>The conversion lives on this side because {@code org.riptide.inventory} depends on
 * neither consumer (AD-10), which is also why the two types exist separately at all.
 * They carry the same three components in the same order, so a transposition here
 * compiles and quietly mislabels every pinned interface: the tests use a distinct value
 * per field for that reason.</p>
 */
final class InventoryPins {

    private InventoryPins() {
    }

    /** The pin for one ifIndex, or {@code null} when the entry pins nothing there. */
    static IfInfo pinFor(final ExporterEntry entry, final int ifIndex) {
        if (entry == null) {
            return null;
        }
        final InterfacePin pin = entry.interfaces().get(ifIndex);
        return pin == null ? null : new IfInfo(pin.name(), pin.alias(), pin.highSpeed());
    }
}
