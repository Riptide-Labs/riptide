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
            // this used to point at riptide.nodes, which 0.9 removed and now fails startup:
            // an operator following it exactly would have been killed by the next boot
            log.error("riptide.snmp.config.definitions has moved and is IGNORED: declare credential "
                    + "sets under riptide.snmp.credentials.<name> and agent ranges in the inventory "
                    + "file named by riptide.inventory.file. SNMP enrichment is NOT active for the "
                    + "{} legacy definition(s).", this.definitions.size());
        }
    }
}
