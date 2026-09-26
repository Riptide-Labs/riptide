/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/** When an exporter entry is polled: on its first flow, or from the moment the inventory loads. */
public enum PollMode {
    ON_FLOW("on-flow"),
    ALWAYS("always");

    private final String key;

    PollMode(final String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    static PollMode parse(final String entryName, final Object value) {
        if (value == null) {
            return ON_FLOW;
        }
        final String text = String.valueOf(value);
        for (final PollMode mode : values()) {
            if (mode.key.equals(text)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Exporter '%s' has an unknown poll value '%s'; write on-flow or always.".formatted(entryName, text));
    }
}
