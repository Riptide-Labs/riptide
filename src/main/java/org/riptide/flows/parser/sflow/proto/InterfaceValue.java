/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

/**
 * An sFlow interface value with its 2-bit format selector (sflow_version_5.txt §5.3):
 * format 0 carries an ifIndex in the low 30 bits ({@code 0x3FFFFFFF} meaning
 * device-internal/unknown), format 1 a drop reason, format 2 a count of multiple
 * destinations. Only format 0 with a real index yields an ifIndex — everything else
 * must never be mistaken for one.
 */
public record InterfaceValue(int format, long value) {

    public static final long INTERNAL = 0x3FFFFFFFL;

    /** Decodes the compact single-word form (top 2 bits = format). */
    public static InterfaceValue compact(final long raw) {
        return new InterfaceValue((int) (raw >>> 30), raw & INTERNAL);
    }

    /** The expanded form carries format and value as separate words. */
    public static InterfaceValue expanded(final long format, final long value) {
        return new InterfaceValue((int) format, value);
    }

    /** The ifIndex, or {@code 0} when this value does not denote one. */
    public int ifIndex() {
        // the expanded form carries a full 32-bit word: reject values that would
        // overflow an ifIndex instead of casting them negative
        if (this.format != 0 || this.value == INTERNAL || this.value > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) this.value;
    }
}
