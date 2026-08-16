/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the published {@link InventorySnapshot}. The loader fails startup before any
 * serving state is published when the inventory file is unreadable or invalid; on
 * success one immutable snapshot instance is published behind this single volatile
 * reference, so a whole-instance swap is the entire concurrency story (AD-3), and a
 * hot reload is exactly such a swap. This bean performs no IO after startup and
 * serves no consumers yet: the enrichers and the poller cut over in story 2.8.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Inventory {

    // volatile, not final: a main-config reload rebinds the credential and polling
    // profiles, and the snapshot's entries hold resolved objects from them (AD-5), so the
    // two have to move together or a rotated credential never reaches a walk
    @NonNull
    private volatile SnmpProfilesConfig profiles;

    @NonNull
    private final InventoryConfig config;

    private volatile InventorySnapshot active = InventorySnapshot.empty();

    @PostConstruct
    public void load() {
        // boot commits through the same path as reload, so the whole-instance swap
        // stays the only way serving state ever changes
        final InventorySnapshot loaded = InventoryLoader.load(this.profiles, this.config.getFile());
        swap(loaded);
        if (this.config.getFile() == null) {
            // silence here would read as "working" while every flow goes unenriched
            log.info("No inventory file configured (riptide.inventory.file): serving the empty inventory");
        } else {
            log.info("Inventory loaded from {}: {} agent ranges, {} enrichment entries",
                    this.config.getFile(), loaded.agentCount(), loaded.exporterCount());
        }
    }

    /**
     * Atomically replaces the serving snapshot (hot reload). One volatile write of a
     * whole immutable instance is the entire concurrency story (AD-3): readers that
     * captured views from the previous snapshot keep a consistent pair of trees.
     */
    public void swap(final InventorySnapshot snapshot) {
        this.active = Objects.requireNonNull(snapshot);
    }

    /**
     * Publishes a snapshot together with the profiles it was built from, which is what a
     * main-config reload needs: credential sets live in the main config, agent ranges
     * resolve them into objects at build time, so rotating a community means rebuilding
     * the inventory rather than swapping either half on its own.
     */
    public synchronized void swap(final SnmpProfilesConfig profiles, final InventorySnapshot snapshot) {
        this.profiles = Objects.requireNonNull(profiles);
        this.active = Objects.requireNonNull(snapshot);
    }

    /**
     * Publishes a snapshot only if the profiles it was parsed against are still the ones
     * serving, and reports whether it did.
     *
     * <p>Two reloaders publish here on separate threads. The inventory watcher reads the
     * profiles, parses a file against them, and commits, and a main-config reload rotating
     * a credential can land in between: committing anyway would pair the new profiles with
     * a snapshot built from the old ones and silently undo the rotation. Losing the race
     * means re-reading and re-parsing, which the caller does on its next cycle.</p>
     */
    public synchronized boolean swapIfProfilesUnchanged(final SnmpProfilesConfig parsedWith,
                                                        final InventorySnapshot snapshot) {
        if (this.profiles != parsedWith) {
            return false;
        }
        this.active = Objects.requireNonNull(snapshot);
        return true;
    }

    /**
     * Rebuilds from {@code file} against {@code profiles} and publishes both, atomically
     * with respect to {@link #swapIfProfilesUnchanged}.
     *
     * <p>The read happens inside the monitor deliberately. A main-config reload rotating a
     * credential has to load the inventory file to resolve it, and the inventory watcher
     * can commit newer file content during that load: publishing afterwards would
     * overwrite it with older content, and neither reloader would notice, because both
     * would consider their own hashes committed. Holding the monitor makes the watcher's
     * compare-and-set fail instead, which it handles by re-parsing on its next cycle.</p>
     */
    public synchronized InventorySnapshot rebuildAndSwap(final SnmpProfilesConfig profiles, final Path file) {
        final InventorySnapshot rebuilt = InventoryLoader.load(profiles, file);
        this.profiles = Objects.requireNonNull(profiles);
        this.active = rebuilt;
        return rebuilt;
    }

    /** Rebuilds without publishing, for a caller that must inspect the result first. */
    public synchronized InventorySnapshot rebuildOnly(final SnmpProfilesConfig profiles, final Path file) {
        return InventoryLoader.load(profiles, file);
    }

    /** The profiles the serving snapshot was built from, for a reloader re-parsing the file. */
    public SnmpProfilesConfig profiles() {
        return this.profiles;
    }

    /** The current snapshot: exactly one volatile read; capture views from it, not from here. */
    public InventorySnapshot snapshot() {
        return this.active;
    }
}
