/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.utils;

public record Tuple<LEFT, RIGHT>(LEFT first, RIGHT second) {
    public static <LEFT, RIGHT> Tuple<LEFT, RIGHT> of(LEFT first, RIGHT second) {
        return new Tuple<>(first, second);
    }
}
