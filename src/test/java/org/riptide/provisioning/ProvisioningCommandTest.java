/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument handling for the subcommands. These paths fail during parsing — before any ClickHouse
 * connection is built — so they need no server; the live provisioning flow is covered by
 * {@code TenantOnboardingIT}.
 */
class ProvisioningCommandTest {

    @Test
    void matchesOnlyKnownSubcommands() {
        assertThat(ProvisioningCommand.matches("onboard")).isTrue();
        assertThat(ProvisioningCommand.matches("offboard")).isTrue();
        assertThat(ProvisioningCommand.matches("collect")).isFalse();
    }

    @Test
    void missingValueForOptionExitsTwoWithUsage() {
        final var err = new ByteArrayOutputStream();
        final int code = ProvisioningCommand.run(
                new String[] {"onboard", "--tenant"}, discard(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("missing value for --tenant").contains("usage:");
    }

    @Test
    void unexpectedPositionalArgExitsTwo() {
        final var err = new ByteArrayOutputStream();
        final int code = ProvisioningCommand.run(
                new String[] {"onboard", "oops"}, discard(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unexpected argument: oops");
    }

    @Test
    void parseQuotaBytesDefaultsAndRejectsNonNumeric() {
        assertThat(ProvisioningCommand.parseQuotaBytes(null)).isPositive();
        assertThat(ProvisioningCommand.parseQuotaBytes("500")).isEqualTo(500L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProvisioningCommand.parseQuotaBytes("lots"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--quota-bytes must be a number");
    }

    @Test
    void parseTtlDaysDefaultsAndRejectsOutOfRange() {
        assertThat(ProvisioningCommand.parseTtlDays(null)).isEqualTo(30);
        assertThat(ProvisioningCommand.parseTtlDays("400")).isEqualTo(400);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProvisioningCommand.parseTtlDays("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--ttl-days must be between 1 and 10950");
        // Oversized intervals wrap ClickHouse's UInt32 DateTime arithmetic (verified: INTERVAL
        // 49710 DAY wraps to a TTL in the past and every insert is silently discarded).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProvisioningCommand.parseTtlDays("49710"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2106");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProvisioningCommand.parseTtlDays("month"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--ttl-days must be a number");
    }

    @Test
    void ttlDaysWithoutCreateSchemaExitsTwo() {
        final var err = new ByteArrayOutputStream();
        final int code = ProvisioningCommand.run(
                new String[] {"onboard", "--admin-url", "http://localhost:1", "--ttl-days", "90",
                        "--tenant", "t", "--org", "o", "--writer-secret", "w", "--reader-secret", "r"},
                discard(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--ttl-days requires --create-schema");
    }

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
