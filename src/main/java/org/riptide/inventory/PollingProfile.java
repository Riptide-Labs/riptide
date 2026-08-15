/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * A named set of poll-behaviour parameters, configured under
 * {@code riptide.snmp.polling.<name>} and referenced by agent ranges. The profile
 * named {@code default} applies to ranges that name none; when the operator defines
 * no such profile, {@link #builtInDefault()} does, carrying exactly the cadence the
 * old global keys defaulted to, so nothing changes pace.
 *
 * <p>A record rather than a bean, because one instance is shared by every unprofiled
 * range in a snapshot and, when the operator defines {@code default}, by every
 * snapshot a reload produces. While it was mutable, a single setter would have
 * retuned the whole fleet with nothing revalidating the result.</p>
 *
 * @param refreshInterval how often each exporter is walked
 * @param snapshotExpiry how long a snapshot stays usable, the staleness backstop
 * @param timeout the per-walk SNMP timeout in milliseconds
 * @param retries the per-walk SNMP retry count, zero meaning a single attempt
 */
@Slf4j
public record PollingProfile(@DefaultValue(DEFAULT_REFRESH_INTERVAL) Duration refreshInterval,
                             @DefaultValue(DEFAULT_SNAPSHOT_EXPIRY) Duration snapshotExpiry,
                             @DefaultValue("" + DEFAULT_TIMEOUT_MS) int timeout,
                             @DefaultValue("" + DEFAULT_RETRIES) int retries) {

    /** Mirrors SnmpPollConfig.refreshIntervalMs, which inherited the old cache retention. */
    static final String DEFAULT_REFRESH_INTERVAL = "PT10M";

    /** Mirrors SnmpPollConfig.snapshotExpiryMs: the 3x staleness backstop. */
    static final String DEFAULT_SNAPSHOT_EXPIRY = "PT30M";

    // ints, not strings: the annotation takes a compile-time constant expression
    // either way, and this keeps a parse out of the code path CodeQL reads as
    // throwing while the two default paths still share one source of truth
    static final int DEFAULT_TIMEOUT_MS = 500;

    static final int DEFAULT_RETRIES = 1;

    /**
     * The implicit {@code default} profile. Built from the same constants the binder
     * defaults to, so the two paths cannot drift; {@code PollingDefaultsGuardTest}
     * pins the values themselves against the classes the poller still reads.
     */
    public static PollingProfile builtInDefault() {
        return new PollingProfile(Duration.parse(DEFAULT_REFRESH_INTERVAL),
                Duration.parse(DEFAULT_SNAPSHOT_EXPIRY),
                DEFAULT_TIMEOUT_MS,
                DEFAULT_RETRIES);
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
        // bounded because these now reach arithmetic the fleet-wide settings used to own:
        // a duration large enough to overflow a nanosecond conversion either throws out of
        // the poll tick, killing every registration's schedule, or wraps negative and walks
        // the endpoint every second
        requireWithinBound(name, "refresh-interval", this.refreshInterval);
        requireWithinBound(name, "snapshot-expiry", this.snapshotExpiry);
        if (expiryShorterThanRefresh()) {
            log.warn("Polling profile '{}' expires snapshots ({}) faster than it refreshes them ({}): "
                    + "a single missed walk blanks enrichment for its exporters", name, this.snapshotExpiry, this.refreshInterval);
        }
    }

    /** A day is far past any sane poll cadence and far short of anything that overflows. */
    private static final Duration MAX_CADENCE = Duration.ofDays(1);

    private static void requireWithinBound(final String name, final String field, final Duration value) {
        if (value.compareTo(MAX_CADENCE) > 0) {
            throw new IllegalStateException(
                    "Polling profile '%s' has a %s of %s, over the %s maximum.".formatted(name, field, value, MAX_CADENCE));
        }
    }

    boolean expiryShorterThanRefresh() {
        return this.snapshotExpiry.compareTo(this.refreshInterval) < 0;
    }
}
