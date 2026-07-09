/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCacheConfig {
    public long retentionMs;
}
