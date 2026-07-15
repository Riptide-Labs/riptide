/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

/** Result of a health check: whether the aspect is up, plus a terse human-readable detail. */
public record Health(boolean up, String detail) {
    public static Health up(final String detail) {
        return new Health(true, detail);
    }

    public static Health down(final String detail) {
        return new Health(false, detail);
    }
}
