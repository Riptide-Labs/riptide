/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * Where the inventory document comes from.
 *
 * <p><b>Why this exists</b>: three sites read through {@link #text()} — boot and the two
 * {@code rebuildAndSwap} calls in {@code ConfigFileReloader} for a credential rotation and its
 * pending retry. Discovery composes a document from two sources, and a composition that reached
 * only the site a report pointed at would rebuild an inventory with no exporters during a
 * rotation, which the regression guard would refuse, wedging rotation until a restart. One seam
 * for those three, so there is nowhere left to miss among them.</p>
 *
 * <p>The inventory watcher ({@code InventoryFileReloader.reload(byte[])}) is the fourth read
 * site, and deliberately does not go through this interface: it gets its bytes from its own
 * {@code FileWatchTrigger}, which polls for changed content rather than reading a current
 * snapshot on demand. It reaches the same two-source composition by a different route. With
 * discovery on, its {@code FileWatchTrigger.Source} is {@code ComposedInventoryDocument}, the
 * same object that is the primary {@code InventoryDocument}, so the watcher and the three sites
 * above read one composition. With discovery off it watches the file directly.</p>
 */
public interface InventoryDocument {

    /**
     * The document as it is now, or {@code null} when no inventory is configured at all, which is
     * valid and means the empty inventory.
     *
     * @throws IllegalStateException when an inventory IS configured and cannot be produced
     */
    String text();

    /**
     * The document as boot reads it, which is {@link #text()} unless an implementation has a
     * reason to serve less rather than refuse to start. Only {@code Inventory.load()} calls this.
     * The watcher and both credential-rotation rebuilds call {@link #text()}, so a degraded answer
     * can never reach a reload by accident: it is a different method, not a flag.
     *
     * <p>The composed discovery document is the one override. When the endpoint cannot be fetched
     * at boot, it serves the file's trees with no exporters, because a flow collector that refuses to
     * start while NetBox is down is worse than one that starts without device names.</p>
     *
     * @throws IllegalStateException when an inventory IS configured and cannot be produced
     */
    default String bootText() {
        return text();
    }

    /** How the document is named in loader errors. */
    String name();
}
