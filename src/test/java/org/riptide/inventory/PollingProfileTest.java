/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingProfileTest {

    @Test
    void misCasedDefaultProfileNameIsRejectedAtBind() {
        // the binder preserves map-key case, but the implicit-default lookup
        // resolves the exact spelling "default" — a 'Default' profile would
        // validate and then be silently ignored by every unprofiled range
        assertThatThrownBy(() -> new SnmpProfilesConfig(Map.of(), Map.of("Default", new PollingProfile())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default")
                .hasMessageContaining("exactly 'default'");
        assertThatCode(() -> new SnmpProfilesConfig(Map.of(), Map.of("default", new PollingProfile())))
                .doesNotThrowAnyException();
    }

    @Test
    void expiryShorterThanRefreshIsAWarningPredicateNotAnError() {
        final PollingProfile tight = new PollingProfile();
        tight.setRefreshInterval(Duration.ofMinutes(10));
        tight.setSnapshotExpiry(Duration.ofMinutes(1));

        assertThat(tight.expiryShorterThanRefresh()).isTrue();
        assertThatCode(() -> tight.validate("tight")).doesNotThrowAnyException();
        assertThat(PollingProfile.builtInDefault().expiryShorterThanRefresh()).isFalse();
    }
}
