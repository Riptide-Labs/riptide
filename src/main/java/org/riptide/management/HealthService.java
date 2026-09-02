/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import org.riptide.flows.Daemon;
import org.riptide.flows.listeners.Listener;
import org.springframework.stereotype.Component;

/**
 * Evaluates collector health for the management endpoints. Deliberately never references
 * ClickHouse. That was decided before the bounded batching queue existed (#382) and holds with it
 * (#541): flows are UDP push, so "not ready" only moves the loss to another layer; recovery
 * convergence typically loses more flows than the queue absorbs; and where Prometheus scrapes
 * through the Service, "not ready" can take /metrics down with it, blinding the signal that
 * explains the outage exactly when it fires. Probes are for scheduling; saturation is for alerting
 * (a sustained persister.batch.droppedRows or failedRows rate, and queueDepth near capacity)
 * ({@code persister.batch.droppedRows}, {@code persister.batch.queueDepth}). Zero configured
 * receivers likewise reports ready: the shipped configuration declares none, and a fresh install
 * must be able to become ready (the startup WARN in Daemon is the operator signal).
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
