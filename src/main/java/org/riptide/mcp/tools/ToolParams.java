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

    /**
     * 30 days: the widest window a single tool call will run.
     *
     * <p>Was justified as "the raw table's retention, past which a longer window has nothing left to
     * read". That is the assumed-retention error twice over: a deployment provisioned with
     * {@code onboard --ttl-days 7} retains less, and the rollups retain 365 days, so a longer window
     * has a great deal left to read whenever a query routes to one. The cap stands as a bound on a
     * single query's cost — but it truncates, so a caller who asks for more is told (#609), and
     * whether it should lift for rollup-routed queries is its own question.</p>
     */
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

    /**
     * The lookback the caller actually asked for, before the cap.
     *
     * <p>Needed so a clamped answer can say it was clamped. Comparing coverage against the capped
     * window alone reported nothing for the very case #609 was filed about: a 90-day request capped
     * to 30 days against a 30-day table came back with no warning, having taught the reader that no
     * warning means nothing is wrong.</p>
     */
    public static int requestedTimeRangeMinutes(final Object raw, final int defaultValue) {
        return boundedInt(raw, defaultValue, Integer.MAX_VALUE);
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
