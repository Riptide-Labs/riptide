/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCacheConfig {
    public long retentionMs;
}
