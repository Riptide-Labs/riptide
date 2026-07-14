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

    private static PrintStream discard() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
