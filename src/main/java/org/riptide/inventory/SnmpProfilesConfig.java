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
 * immutable: no caller can edit the profile set behind the loader's back.</p>
 *
 * <p>The copy's shape carries no weight with CodeQL's
 * java/internal-representation-exposure, whatever an earlier version of this
 * comment claimed. Reading it as the reason alerts 143 and 144 cleared is what
 * sent the first of five attempts on the same finding in {@code ProfilingStatus}
 * chasing the shape of an expression that never mattered. That rule has no
 * notion of a defensive copy, and for a record it counts the implicit component
 * assignment regardless of what the constructor writes. What keeps this record
 * unflagged is the rule's other half, which needs a caller that mutates its
 * argument after passing it, and no call site does. Should one ever appear, no
 * reshaping will help; see the comment on
 * {@link org.riptide.profiling.ProfilingConfiguration.ProfilingStatus}.</p>
 */
@ConfigurationProperties(prefix = "riptide.snmp")
public record SnmpProfilesConfig(Map<String, CredentialSet> credentials, Map<String, PollingProfile> polling) {

    public SnmpProfilesConfig {
        credentials = credentials != null ? Map.copyOf(credentials) : Map.of();
        polling = polling != null ? Map.copyOf(polling) : Map.of();
        // sorted so the first-named violation is deterministic across JVM runs
        // (Map.copyOf iteration order is salt-randomized)
        new TreeMap<>(credentials).forEach((name, set) -> set.validate(name));
        new TreeMap<>(polling).forEach((name, profile) -> {
            // the binder preserves map-key case, but ranges that omit 'polling'
            // resolve the exact name "default"; a mis-cased spelling would
            // validate and then be silently ignored by that lookup
            if (name.equalsIgnoreCase("default") && !name.equals("default")) {
                throw new IllegalStateException(
                        ("Polling profile '%s': the default profile must be spelled exactly 'default', "
                                + "because agent ranges without a 'polling' key resolve that name.").formatted(name));
            }
            profile.validate(name);
        });
    }
}
