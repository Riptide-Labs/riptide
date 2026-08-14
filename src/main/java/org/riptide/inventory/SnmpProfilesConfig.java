/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.TreeMap;

/**
 * The small Spring-bound profile maps the inventory loader resolves references
 * against: named credential sets and named polling profiles. These stay on the
 * property binder (they are small and carry {@link org.riptide.secrets.SecretRef}
 * values); the bulk inventory lives in the direct-parsed file named by
 * {@code riptide.inventory.file}.
 *
 * <p>Constructor-bound and defensively copied, so the maps handed out are
 * immutable: no caller can edit the profile set behind the loader's back
 * (CodeQL java/internal-representation-exposure).</p>
 */
@ConfigurationProperties(prefix = "riptide.snmp")
public record SnmpProfilesConfig(Map<String, CredentialSet> credentials, Map<String, PollingProfile> polling) {

    public SnmpProfilesConfig {
        credentials = credentials != null ? Map.copyOf(credentials) : Map.of();
        polling = polling != null ? Map.copyOf(polling) : Map.of();
        // sorted so the first-named violation is deterministic across JVM runs
        // (Map.copyOf iteration order is salt-randomized)
        new TreeMap<>(credentials).forEach((name, set) -> set.validate(name));
    }
}
