/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.PollingProfile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the built-in polling defaults equal across the three classes that declare
 * them independently, so profiled and unprofiled ranges cannot silently diverge
 * (2.3 review finding). Shrinks with the legacy classes when they retire.
 */
class PollingDefaultsGuardTest {

    @Test
    void builtInDefaultsAgreeAcrossAllDeclarations() {
        final PollingProfile profile = PollingProfile.builtInDefault();
        final SnmpDefinition legacyDefinition = new SnmpDefinition();
        final SnmpPollConfig fleetConfig = new SnmpPollConfig();

        assertThat(profile.timeout()).isEqualTo(legacyDefinition.getTimeout());
        assertThat(profile.retries()).isEqualTo(legacyDefinition.getRetries());
        assertThat(profile.refreshInterval()).isEqualTo(Duration.ofMillis(fleetConfig.getRefreshIntervalMs()));
        assertThat(profile.snapshotExpiry()).isEqualTo(Duration.ofMillis(fleetConfig.getSnapshotExpiryMs()));
    }
}
