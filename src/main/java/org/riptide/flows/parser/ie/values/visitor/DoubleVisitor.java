/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values.visitor;

import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.springframework.stereotype.Service;

@Service
public class DoubleVisitor implements ValueVisitor<Double> {
    @Override
    public Class<Double> targetClass() {
        return Double.class;
    }

    @Override
    public Double visit(FloatValue value) {
        return value.getValue();
    }

    @Override
    public Double visit(SignedValue value) {
        return value.getValue().doubleValue();
    }

    @Override
    public Double visit(UnsignedValue value) {
        // UnsignedLong.doubleValue() without the box (Guava, Apache-2.0): below 2^63 the bits are
        // the value; above, halve with the low bit kept for rounding, convert, and double.
        final long bits = value.bits();
        return bits >= 0 ? (double) bits : (double) ((bits >>> 1) | (bits & 1)) * 2.0;
    }
}
