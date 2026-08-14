/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Owns the published {@link InventorySnapshot}. The loader fails startup before any
 * serving state is published when the inventory file is unreadable or invalid; on
 * success one immutable snapshot instance is published behind this single volatile
 * reference, so a whole-instance swap is the entire concurrency story (AD-3) once
 * hot reload lands (story 2.2). This bean performs no IO after startup and serves
 * no consumers yet: the enrichers and the poller cut over in story 2.8.
 */
@Component
@RequiredArgsConstructor
public class Inventory {

    @NonNull
    private final SnmpProfilesConfig profiles;

    @NonNull
    private final InventoryConfig config;

    private volatile InventorySnapshot active = InventorySnapshot.empty();

    @PostConstruct
    public void load() {
        this.active = InventoryLoader.load(this.profiles, this.config.getFile());
    }

    /** The current snapshot: exactly one volatile read; capture views from it, not from here. */
    public InventorySnapshot snapshot() {
        return this.active;
    }
}
