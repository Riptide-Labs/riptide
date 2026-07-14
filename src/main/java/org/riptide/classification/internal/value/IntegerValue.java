/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.value;

public class IntegerValue {
    private Integer value;

    public IntegerValue(final StringValue input) {
        if (input == null || input.isNullOrEmpty()) {
            this.value = null;
        } else {
            try {
                this.value = Integer.parseInt(input.getValue());
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("Value '" + input.getValue() + "' is not a valid number", ex);
            }
        }
    }

    public boolean isNull() {
        return value == null;
    }

    public int getValue() {
        return value.intValue(); // Check before with isNull() otherwise NPE may be thrown
    }
}
