/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * The {@code riptide.snmp.config.definitions} tree moved to {@code riptide.nodes} — this
 * bean only exists to fail loudly instead of silently ignoring legacy configuration.
 */
@Data
@Slf4j
@ConfigurationProperties(prefix = "riptide.snmp.config")
public class SnmpConfigMigrationWarning {

    private List<Map<String, Object>> definitions = List.of();

    @PostConstruct
    void warnAboutLegacyConfiguration() {
        if (!this.definitions.isEmpty()) {
            log.error("riptide.snmp.config.definitions has moved and is IGNORED: configure nodes via "
                    + "riptide.nodes[].{label,subnet-address,observation-domain,snmp.*} instead "
                    + "(see the NODES EXAMPLE in application.properties). SNMP enrichment is NOT "
                    + "active for the {} legacy definition(s).", this.definitions.size());
        }
    }
}
