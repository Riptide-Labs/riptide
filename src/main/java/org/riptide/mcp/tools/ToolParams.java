/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import java.util.Map;

/**
 * Argument coercion shared by the MCP tools.
 *
 * <p>Tool arguments arrive as untyped JSON, so a value can be a number, a numeric string, absent,
 * or nonsense. Every tool needs the same answer to that: clamp what is usable into a sane range and
 * fall back to the tool's default otherwise, so a malformed argument degrades to a default instead
 * of failing the call or reaching ClickHouse as an unbounded scan.
 */
public final class ToolParams {

    /** 30 days: the raw table's retention, past which a longer window has nothing left to read. */
    static final int MAX_TIME_RANGE_MINUTES = 43_200;

    /** Row ceiling for tools that take an explicit limit, independent of a result-set cap. */
    static final int MAX_LIMIT = 500;

    private ToolParams() {
        // Utility class
    }

    /** The tool arguments, or an empty map when the caller sent none. */
    public static Map<String, Object> safe(final Map<String, Object> params) {
        return params != null ? params : Map.of();
    }

    /** A lookback in minutes, clamped to a window the data can actually cover. */
    public static int timeRangeMinutes(final Object raw, final int defaultValue) {
        return boundedInt(raw, defaultValue, MAX_TIME_RANGE_MINUTES);
    }

    /** A row limit, clamped so a caller cannot ask for an unbounded result set. */
    public static int limit(final Object raw, final int defaultValue) {
        return boundedInt(raw, defaultValue, MAX_LIMIT);
    }

    private static int boundedInt(final Object raw, final int defaultValue, final int max) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            final int value = raw instanceof Number num
                    ? num.intValue()
                    : Integer.parseInt(raw.toString().trim());
            return Math.min(Math.max(1, value), max);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }
}
