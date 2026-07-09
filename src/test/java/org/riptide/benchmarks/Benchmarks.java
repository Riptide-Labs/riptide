/*
 * Copyright 2025 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks;

public final class Benchmarks {
    private Benchmarks() {
    }

    public static void main(final String... args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
