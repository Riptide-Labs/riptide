/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollKeyMigrationCheckTest {

    private static void check(final String key) {
        final MockEnvironment environment = new MockEnvironment().withProperty(key, "1000");
        PollKeyMigrationCheck.failOnRetiredPollKeys(environment.getPropertySources());
    }

    @Test
    void retiredSpellingsFailInEveryRelaxedForm() {
        for (final String key : new String[]{
                "riptide.snmp.poll.refresh-interval-ms",
                "riptide.snmp.poll.refreshIntervalMs",
                "riptide.snmp.poll.snapshot-expiry-ms",
                "riptide.snmp.poll.snapshotExpiryMs",
                "riptide.snmp.poll.refresh_interval_ms",
                "riptide.snmp.poll.snapshot_expiry_ms",
                "Riptide.Snmp.Poll.Refresh-Interval-Ms",
                "RIPTIDE_SNMP_POLL_REFRESH_INTERVAL_MS",
                "RIPTIDE_SNMP_POLL_SNAPSHOT_EXPIRY_MS",
                // the canonical Spring env mapping strips dashes instead of replacing them
                "RIPTIDE_SNMP_POLL_REFRESHINTERVALMS",
                "RIPTIDE_SNMP_POLL_SNAPSHOTEXPIRYMS"}) {
            assertThatThrownBy(() -> check(key))
                    .as("key %s", key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("riptide.snmp.polling");
        }
    }

    @Test
    void fleetKeysAndProfileKeysDoNotTrip() {
        for (final String key : new String[]{
                "riptide.snmp.poll.pool-width",
                "riptide.snmp.poll.max-exporters",
                "riptide.snmp.poll.dead-endpoint-base-ms",
                "riptide.snmp.polling.default.refresh-interval",
                "riptide.snmp.polling.slow.snapshot-expiry",
                "RIPTIDE_SNMP_POLLING_DEFAULT_REFRESH_INTERVAL",
                "RIPTIDE_SNMP_POLLING_DEFAULT_REFRESHINTERVAL"}) {
            assertThatCode(() -> check(key)).as("key %s", key).doesNotThrowAnyException();
        }
    }
}
