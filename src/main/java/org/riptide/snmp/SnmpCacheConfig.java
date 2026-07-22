/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention for interface information: the SNMP poll cache and the exporter option
 * table. A JavaBean property (not a bare public field) on purpose — Spring's binder
 * silently skips fields without accessors, which would leave the caches at a 0 ms
 * TTL regardless of configuration.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCacheConfig {

    private long retentionMs = 600_000;

    /**
     * TTL for cached lookup misses. Bounds the cost of an ifIndex that stays
     * unresolvable (absent from the agent's IF-MIB, or an unreachable agent) to one
     * table walk per TTL instead of one per flow. 0 disables negative caching.
     */
    private long negativeRetentionMs = 60_000;

    /**
     * Back-off TTL for endpoints whose walk timed out: the whole endpoint is considered
     * dead and no walks are issued for any of its ifIndexes until the TTL expires, so an
     * unreachable exporter costs one walk per TTL regardless of how many ifIndexes its
     * flows reference. Cleared by config hot-reload. 0 disables the endpoint back-off.
     */
    private long deadEndpointRetentionMs = 60_000;
}
