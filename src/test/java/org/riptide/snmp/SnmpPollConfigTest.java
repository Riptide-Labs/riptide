/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SnmpPollConfigTest {

    @Test
    void defaultsInheritTheOldCadenceSoUpgradesDoNotChangePace() {
        final var config = new SnmpPollConfig();

        // deliberately equal to the retiring riptide.snmp.cache.retention-ms default
        assertThat(config.getRefreshIntervalMs()).isEqualTo(600_000L);
        // and to the retiring flat dead-endpoint-retention-ms default
        assertThat(config.getDeadEndpointBaseMs()).isEqualTo(60_000L);
    }

    @Test
    void expiryOutlivesRefreshSoAStaleSnapshotIsStillServable() {
        final var config = new SnmpPollConfig();

        // the whole reason these are two properties and not one: if expiry were not longer
        // than refresh, a late walk would blank enrichment instead of serving stale names
        assertThat(config.getSnapshotExpiryMs()).isGreaterThan(config.getRefreshIntervalMs());
    }

    /**
     * Guards the trap recorded in {@link SnmpOptionsConfig}: Spring's binder silently skips
     * fields without accessors, which would leave every interval at its field default no
     * matter what an operator configured. A relaxed-binding round trip proves the accessors
     * are really there.
     */
    @Test
    void everyPropertyBindsFromRelaxedNames() {
        final var environment = new MockEnvironment()
                .withProperty("riptide.snmp.poll.refresh-interval-ms", "1000")
                .withProperty("riptide.snmp.poll.snapshot-expiry-ms", "2000")
                .withProperty("riptide.snmp.poll.pool-width", "9")
                .withProperty("riptide.snmp.poll.deregister-after", "5")
                .withProperty("riptide.snmp.poll.dead-endpoint-base-ms", "3000")
                .withProperty("riptide.snmp.poll.dead-endpoint-ceiling-ms", "4000");

        final var bound = new Binder(ConfigurationPropertySources.get(environment))
                .bind("riptide.snmp.poll", SnmpPollConfig.class)
                .orElseThrow(() -> new AssertionError("nothing bound from riptide.snmp.poll.*"));

        assertThat(bound.getRefreshIntervalMs()).isEqualTo(1000L);
        assertThat(bound.getSnapshotExpiryMs()).isEqualTo(2000L);
        assertThat(bound.getPoolWidth()).isEqualTo(9);
        assertThat(bound.getDeregisterAfter()).isEqualTo(5);
        assertThat(bound.getDeadEndpointBaseMs()).isEqualTo(3000L);
        assertThat(bound.getDeadEndpointCeilingMs()).isEqualTo(4000L);
    }
}
