/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Interface data is polled on a schedule now rather than cached on demand, so the knob that used
 * to control its freshness no longer does.
 *
 * <p>This exists because the failure is otherwise silent and looks like nothing changed. An
 * operator who tuned {@code riptide.snmp.cache.retention-ms} down to a minute for fresher
 * interface names still has a valid, still-read property — it now only sizes the exporter option
 * table — while actual SNMP freshness reverts to the default poll cadence of ten minutes
 * (tunable per polling profile, {@code riptide.snmp.polling.<name>.refresh-interval}). Nothing
 * fails, nothing warns, and the names are simply staler than configured.
 *
 * <p>Bound separately from {@link SnmpCacheConfig} rather than adding a {@code @PostConstruct}
 * there, because the warning has to distinguish "left at the default" from "deliberately set",
 * and a bound default is indistinguishable from an explicit one on the config object itself.
 */
@Data
@Slf4j
@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCacheMigrationWarning {

    /** {@code null} when unset, which is the whole point: it separates default from explicit. */
    private Long retentionMs;

    private Long negativeRetentionMs;

    private Long deadEndpointRetentionMs;

    /**
     * Warns rather than adopting the old value, which reverses the plan recorded in the change.
     *
     * <p>Adopting looked like the kind option: an operator who set {@code retention-ms=60000} for
     * fresher interface names would keep their intent. It is the dangerous one. The old property
     * was a cache TTL — how long an answer stays usable — and the new one is a poll interval, how
     * often to ask. Carrying 60000 across turns "keep answers for a minute" into "walk every
     * exporter every minute", ten times the agent load the operator had before, silently, on
     * upgrade. Against a change whose entire purpose is being gentler to exporters, that is the
     * wrong failure. Warn, and let them choose.
     */
    @PostConstruct
    void warnAboutRepurposedProperties() {
        if (this.retentionMs != null) {
            log.warn("riptide.snmp.cache.retention-ms={} is IGNORED and has NOT been carried over. It was a "
                            + "cache TTL; the poll cadence (riptide.snmp.polling.<name>.refresh-interval, "
                            + "default 10m) is a poll interval, so adopting your value would change how often "
                            + "riptide walks your exporters rather than how long it keeps answers. Set a "
                            + "polling profile deliberately if you want a different cadence. To size the "
                            + "exporter option table, use riptide.snmp.options.retention-ms.",
                    this.retentionMs);
        }
        if (this.negativeRetentionMs != null) {
            log.warn("riptide.snmp.cache.negative-retention-ms={} is IGNORED. Misses are no longer cached "
                            + "separately: an ifIndex absent from a polled snapshot is a known absence, so "
                            + "there is nothing to expire. A newly added interface now appears at the next "
                            + "poll rather than within this window.", this.negativeRetentionMs);
        }
        if (this.deadEndpointRetentionMs != null) {
            log.warn("riptide.snmp.cache.dead-endpoint-retention-ms={} is IGNORED. Unreachable endpoints "
                            + "now back off exponentially between riptide.snmp.poll.dead-endpoint-base-ms "
                            + "and dead-endpoint-ceiling-ms rather than retrying at a fixed interval.",
                    this.deadEndpointRetentionMs);
        }
    }
}
