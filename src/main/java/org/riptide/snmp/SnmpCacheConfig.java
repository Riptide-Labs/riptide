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
}
