/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import org.riptide.flows.Daemon;
import org.riptide.flows.listeners.Listener;
import org.springframework.stereotype.Component;

/**
 * Evaluates collector health for the management endpoints. Deliberately never references ClickHouse:
 * there is no write buffer and a single collector has no failover, so gating readiness on ClickHouse
 * would only add load-balancer convergence loss for no benefit (see the add-health-endpoints design).
 */
@Component
public class HealthService {

    private final Daemon daemon;

    public HealthService(final Daemon daemon) {
        this.daemon = daemon;
    }

    /**
     * Process-level liveness: booting is not a fatal state, so it stays up until the receivers have
     * been started; after that, a receiver whose socket has died is a fatal state warranting a restart.
     */
    public Health liveness() {
        if (!this.daemon.isStarted()) {
            return Health.up("starting");
        }
        return receivers();
    }

    /** Readiness: the collector can do useful work — it has started and every receiver is listening. */
    public Health readiness() {
        if (!this.daemon.isStarted()) {
            return Health.down("starting");
        }
        return receivers();
    }

    private Health receivers() {
        final var down = this.daemon.getListeners().stream()
                .filter(listener -> !listener.isListening())
                .map(Listener::getName)
                .toList();
        return down.isEmpty()
                ? Health.up("receivers listening")
                : Health.down("receivers not listening: " + String.join(",", down));
    }
}
