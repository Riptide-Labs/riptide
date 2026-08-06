/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention for interface names exporters push as v9/IPFIX option records.
 *
 * <p>Split out of the retired {@code riptide.snmp.cache} block, which this used to derive from at
 * twice the SNMP retention. That derivation no longer models anything: how long a pushed option
 * record stays valid depends on how often the <em>exporter</em> re-sends it, which has nothing to
 * do with how often riptide polls. The coupling only ever held because the old SNMP retention
 * default happened to match Cisco's default option-table cadence.
 *
 * <p>JavaBean properties, for the reason the retired config recorded: Spring's binder silently
 * skips fields without accessors, which would leave the TTL at 0.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.snmp.options")
public class SnmpOptionsConfig {

    /**
     * How long a pushed interface option record is trusted, in milliseconds.
     *
     * <p>Twice the common exporter cadence (Cisco re-sends option tables every 600 s by default).
     * A 1x TTL would race every refresh, so a single lost option packet would unenrich flows for a
     * whole cycle. The default preserves exactly the value the retired
     * {@code 2 * riptide.snmp.cache.retention-ms} produced.
     */
    private long retentionMs = 1_200_000;
}
