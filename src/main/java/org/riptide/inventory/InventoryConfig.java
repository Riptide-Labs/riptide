/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Names the dedicated inventory file holding the bulk {@code riptide.snmp.agents}
 * and {@code riptide.exporters} trees. Unset means an empty inventory (valid).
 * Inventory via environment variables or {@code spring.config.import} is
 * unsupported: the file is direct-parsed, never property-bound.
 */
@Data
@ConfigurationProperties(prefix = "riptide.inventory")
public class InventoryConfig {

    private Path file;
}
