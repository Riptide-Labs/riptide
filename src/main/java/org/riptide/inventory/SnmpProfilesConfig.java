/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The small Spring-bound profile maps the inventory loader resolves references
 * against: named credential sets and named polling profiles. These stay on the
 * property binder (they are small and carry {@link org.riptide.secrets.SecretRef}
 * values); the bulk inventory lives in the direct-parsed file named by
 * {@code riptide.inventory.file}.
 */
@Data
@ConfigurationProperties(prefix = "riptide.snmp")
public class SnmpProfilesConfig {

    private Map<String, CredentialSet> credentials = new LinkedHashMap<>();

    private Map<String, PollingProfile> polling = new LinkedHashMap<>();
}
