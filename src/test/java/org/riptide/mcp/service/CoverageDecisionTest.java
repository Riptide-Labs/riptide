/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shortfall decision, at the level it is actually made.
 *
 * <p>{@code CoverageReportingIT} drives this through a real server, which is where the interesting
 * facts live — but it cannot reach the empty-table rule. On an empty {@code MergeTree}
 * {@code min(timestamp)} returns the epoch, so the probe reports roughly 29.8 million covered
 * minutes and the range comparison alone already answers "covered". A mutation deleting the
 * row-count clause survived the whole integration suite for that reason.</p>
 *
 * <p>These cases reach it directly, so the rule is held whatever the server returns.</p>
 */
class CoverageDecisionTest {

    @Test
    void anEmptyTableIsNeverShortHoweverFewMinutesItReports() {
        assertThat(RiptideMcpService.isShort(0, 0, 129_600))
                .as("holding nothing is not the same as not reaching far enough")
                .isFalse();
        assertThat(RiptideMcpService.isShort(0, 29_795_707, 129_600))
                .as("and the epoch sentinel must not be what decides it")
                .isFalse();
    }

    @Test
    void aTableThatDoesNotReachBackFarEnoughIsShort() {
        assertThat(RiptideMcpService.isShort(1, 4_320, 129_600)).isTrue();
    }

    @Test
    void theShorterOfTheCapAndTheDataDecides() {
        // the table holds 60 days, the query ran for 30, the caller asked for 90: short by the cap
        assertThat(RiptideMcpService.isShort(1, Math.min(86_400, 43_200), 129_600)).isTrue();
        // the cap is generous, the data is not: short by retention
        assertThat(RiptideMcpService.isShort(1, Math.min(4_320, 43_200), 43_200)).isTrue();
    }

    @Test
    void aTableThatReachesTheRequestedRangeIsNot() {
        assertThat(RiptideMcpService.isShort(1, 129_600, 129_600))
                .as("exactly reaching the start is covered, not short")
                .isFalse();
        assertThat(RiptideMcpService.isShort(1, 200_000, 129_600)).isFalse();
    }
}
