/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Interface-table polling. Replaces the demand-filled cache configured by
 * {@link SnmpCacheConfig}: exporters are polled on a schedule spread across the refresh
 * interval, and enrichment reads the resulting snapshot without ever issuing SNMP itself.
 *
 * <p>Defaults are chosen so nothing changes pace on upgrade — {@link #refreshIntervalMs}
 * equals the old cache retention and {@link #deadEndpointBaseMs} equals the old flat
 * back-off — so the only behavioural changes are the intended ones.
 *
 * <p>JavaBean properties (not bare public fields) on purpose, for the reason recorded in
 * {@link SnmpCacheConfig}: Spring's binder silently skips fields without accessors, which
 * would leave every interval at 0.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.snmp.poll")
public class SnmpPollConfig {

    /**
     * How often each registered exporter's interface table is re-walked.
     *
     * <p>Inherits the old {@code riptide.snmp.cache.retention-ms} default. Interface names
     * change rarely, so this is a freshness knob rather than a correctness one — a new
     * interface becomes visible at the next walk.
     */
    private long refreshIntervalMs = 600_000;

    /**
     * How long a snapshot stays usable once it stops being refreshed.
     *
     * <p>Deliberately distinct from {@link #refreshIntervalMs}, which is the single most
     * important part of this configuration. Refresh is how fresh the data is kept; expiry is
     * the backstop bounding staleness across ifIndex reassignment after a device reboot
     * (RFC 2863). A snapshot older than the refresh interval but within this window is still
     * served, because a twelve-minute-old interface name beats no interface name.
     *
     * <p>The 3x default tolerates two consecutive failed refreshes while keeping post-reboot
     * wrongness to thirty minutes. Shorten it and a couple of missed walks blank enrichment;
     * lengthen it and a renumbered device serves wrong names for longer.
     */
    private long snapshotExpiryMs = 1_800_000;

    /**
     * Ceiling on interface walks in flight across the whole fleet.
     *
     * <p>Fixed rather than scaled with exporter count: a fixed ceiling is the bound actually
     * wanted, and it is what turns a mass restart from a burst into a drain. Per-endpoint
     * concurrency is always one regardless of this value.
     */
    private int poolWidth = 4;

    /**
     * Refresh intervals of silence after which an exporter stops being polled.
     *
     * <p>Registration is driven by flow arrival, so this is what stops riptide polling a
     * device that has gone quiet.
     */
    private int deregisterAfter = 3;

    /**
     * First retry delay after a walk times out, doubling up to {@link #deadEndpointCeilingMs}.
     *
     * <p>Inherits the old flat {@code dead-endpoint-retention-ms} default. Back-off is not
     * cosmetic here: a walk against an unreachable agent holds a pool slot for its whole
     * timeout, so retrying at a fixed interval lets dead exporters starve live ones.
     */
    private long deadEndpointBaseMs = 60_000;

    /** Longest retry delay for an endpoint that keeps failing. */
    private long deadEndpointCeilingMs = 1_800_000;

    /**
     * Ceiling on retained snapshots, counted in exporters.
     *
     * <p>Registration follows flow arrival, so the population is whatever sends flows — including
     * a spoofed source spraying addresses. The bound is expressed in exporters rather than in
     * interface entries because that is the dimension an attacker controls here; the row count per
     * exporter is bounded by the device's real interface count.
     */
    private int maxExporters = 4_096;
}
