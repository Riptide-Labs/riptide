/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.riptide.snmp.collect.CollectionDefinitions;

import java.time.Duration;

import java.util.List;
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
        assertThatThrownBy(() -> new SnmpProfilesConfig(Map.of(), Map.of("Default", PollingProfile.builtInDefault())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default")
                .hasMessageContaining("exactly 'default'");
        assertThatCode(() -> new SnmpProfilesConfig(Map.of(), Map.of("default", PollingProfile.builtInDefault())))
                .doesNotThrowAnyException();
    }

    @Test
    void expiryShorterThanRefreshIsAWarningPredicateNotAnError() {
        final PollingProfile tight = new PollingProfile(
                Duration.ofMinutes(10), Duration.ofMinutes(1), 500, 1, List.of());

        assertThat(tight.expiryShorterThanRefresh()).isTrue();
        assertThatCode(() -> tight.validate("tight")).doesNotThrowAnyException();
        assertThat(PollingProfile.builtInDefault().expiryShorterThanRefresh()).isFalse();
    }

    @Test
    void collectDefaultsToEmptyAndResolvesBuiltInNames() {
        final var profile = PollingProfile.builtInDefault();
        assertThat(profile.collect()).isEmpty();
        assertThat(profile.definitions()).isEmpty();

        final var counters = new PollingProfile(Duration.ofSeconds(60), Duration.ofMinutes(30), 500, 1,
                List.of("if-mib-interfaces"));
        assertThat(counters.definitions()).containsExactly(CollectionDefinitions.IF_MIB_INTERFACES);
    }

    @Test
    void anUnknownCollectionNameIsRefusedByValidate() {
        final var profile = new PollingProfile(Duration.ofSeconds(60), Duration.ofMinutes(30), 500, 1,
                List.of("ip-mib"));
        assertThatThrownBy(() -> profile.validate("brisk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.snmp.polling.brisk.collect")
                .hasMessageContaining("'ip-mib'")
                .hasMessageContaining("if-mib-interfaces");
    }

    @Test
    void aTimeoutThatCannotFitTheWalkBudgetIsRefusedWhenCollecting() {
        // 20 s timeout x 2 attempts = 40 s per PDU, budget is 80% of 30 s = 24 s
        final var profile = new PollingProfile(Duration.ofSeconds(30), Duration.ofMinutes(30), 20_000, 1,
                List.of("if-mib-interfaces"));
        assertThatThrownBy(() -> profile.validate("brisk"))
                .hasMessageContaining("timeout")
                .hasMessageContaining("24");
        // without a collection the same timeout is fine: the two-minute enrichment budget applies
        new PollingProfile(Duration.ofSeconds(30), Duration.ofMinutes(30), 20_000, 1, List.of()).validate("brisk");
    }

    @Test
    void walkBudgetIsEightyPercentOfTheInterval() {
        assertThat(PollingProfile.walkBudget(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(48));
    }
}
