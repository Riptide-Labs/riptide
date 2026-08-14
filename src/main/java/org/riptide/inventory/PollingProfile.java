/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * A named set of poll-behaviour parameters, configured under
 * {@code riptide.snmp.polling.<name>} and referenced by agent ranges. The profile
 * named {@code default} applies to ranges that name none; when the operator defines
 * no such profile, {@link #builtInDefault()} does, carrying exactly the cadence the
 * old global keys defaulted to, so nothing changes pace.
 */
@Data
@Slf4j
public class PollingProfile {

    /** Mirrors SnmpPollConfig.refreshIntervalMs, which inherited the old cache retention. */
    private Duration refreshInterval = Duration.ofMillis(600_000);

    /** Mirrors SnmpPollConfig.snapshotExpiryMs: the 3x staleness backstop. */
    private Duration snapshotExpiry = Duration.ofMillis(1_800_000);

    private int timeout = 500;

    private int retries = 1;

    /** The implicit {@code default} profile: a fresh instance carrying the built-in values. */
    public static PollingProfile builtInDefault() {
        return new PollingProfile();
    }

    /**
     * The shape contract, callable by any producer (the {@link CredentialSet}
     * pattern): positive timeout and durations, non-negative retries (zero retries
     * is a legal SNMP setting meaning a single attempt). Errors name the profile.
     * Expiry shorter than refresh is a warning, not an error: it bounds staleness
     * tighter than the refresh can deliver, which may be intended.
     */
    public void validate(final String name) {
        if (this.timeout <= 0) {
            throw new IllegalStateException(
                    "Polling profile '%s' has a non-positive timeout (%d ms).".formatted(name, this.timeout));
        }
        if (this.retries < 0) {
            throw new IllegalStateException(
                    "Polling profile '%s' has negative retries (%d).".formatted(name, this.retries));
        }
        if (this.refreshInterval == null || this.refreshInterval.isZero() || this.refreshInterval.isNegative()) {
            throw new IllegalStateException(
                    "Polling profile '%s' has a non-positive refresh-interval (%s).".formatted(name, this.refreshInterval));
        }
        if (this.snapshotExpiry == null || this.snapshotExpiry.isZero() || this.snapshotExpiry.isNegative()) {
            throw new IllegalStateException(
                    "Polling profile '%s' has a non-positive snapshot-expiry (%s).".formatted(name, this.snapshotExpiry));
        }
        if (expiryShorterThanRefresh()) {
            log.warn("Polling profile '{}' expires snapshots ({}) faster than it refreshes them ({}): "
                    + "a single missed walk blanks enrichment for its exporters", name, this.snapshotExpiry, this.refreshInterval);
        }
    }

    boolean expiryShorterThanRefresh() {
        return this.snapshotExpiry.compareTo(this.refreshInterval) < 0;
    }
}
