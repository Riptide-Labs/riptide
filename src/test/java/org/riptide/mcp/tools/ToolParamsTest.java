/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool arguments arrive as untyped JSON, so every one of these shapes reaches a tool in practice.
 */
class ToolParamsTest {

    @Test
    void readsNumericAndStringArguments() {
        assertThat(ToolParams.timeRangeMinutes(60, 15)).isEqualTo(60);
        assertThat(ToolParams.timeRangeMinutes("60", 15)).isEqualTo(60);
        assertThat(ToolParams.timeRangeMinutes(" 60 ", 15)).isEqualTo(60);
        // JSON numbers deserialize to Integer or Double depending on the literal.
        assertThat(ToolParams.timeRangeMinutes(60.0d, 15)).isEqualTo(60);
    }

    @Test
    void fallsBackToTheDefaultForAbsentOrUnusableArguments() {
        assertThat(ToolParams.timeRangeMinutes(null, 15)).isEqualTo(15);
        assertThat(ToolParams.timeRangeMinutes("", 15)).isEqualTo(15);
        assertThat(ToolParams.timeRangeMinutes("not-a-number", 15)).isEqualTo(15);
        assertThat(ToolParams.limit(null, 20)).isEqualTo(20);
        assertThat(ToolParams.limit("junk", 20)).isEqualTo(20);
    }

    /** A window past the raw table's retention reads nothing, and zero or negative reads nothing at all. */
    @Test
    void clampsTimeRangeIntoAWindowTheDataCanCover() {
        assertThat(ToolParams.timeRangeMinutes(0, 15)).isEqualTo(1);
        assertThat(ToolParams.timeRangeMinutes(-99, 15)).isEqualTo(1);
        assertThat(ToolParams.timeRangeMinutes(Integer.MAX_VALUE, 15))
                .isEqualTo(ToolParams.MAX_TIME_RANGE_MINUTES);
    }

    @Test
    void clampsLimitSoACallerCannotAskForAnUnboundedResultSet() {
        assertThat(ToolParams.limit(0, 20)).isEqualTo(1);
        assertThat(ToolParams.limit(10_000, 20)).isEqualTo(ToolParams.MAX_LIMIT);
    }

    @Test
    void treatsMissingArgumentsAsAnEmptyMap() {
        assertThat(ToolParams.safe(null)).isEmpty();
        assertThat(ToolParams.safe(Map.of("a", 1))).containsEntry("a", 1);
    }
}
