/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * The fixture pieces the classification reload suites share: the CSV shape a rules server
 * answers with, and a poll-free wait.
 *
 * <p>Its own class because the alternative is a second copy. The rules header and the
 * metric names are the kind of detail that changes once and then has to be found
 * everywhere, and #561 was merged specifically because two copies of one thing had drifted
 * apart without either copy's tests noticing.</p>
 */
final class ClassificationRulesTestSupport {

    /** The importer's expected column order; a row that does not match it parses to nothing. */
    static final String HEADER =
            "name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional\n";

    private ClassificationRulesTestSupport() {
    }

    /** A one-rule ruleset naming everything on port 80 {@code name}. */
    static String rules(final String name) {
        return HEADER + name + ";;;;;80;;false\n";
    }

    /**
     * Waits for a condition, failing with what was being waited for rather than timing out
     * anonymously.
     *
     * @param what named in the failure, so a timeout says which property did not hold
     * @param ceiling how long to wait; a caller whose property is a timing one should
     *     derive this from that timing rather than picking a number
     */
    static void await(final String what, final Duration ceiling, final BooleanSupplier condition)
            throws InterruptedException {
        final long deadline = System.nanoTime() + ceiling.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        fail("timed out after %s waiting for %s".formatted(ceiling, what));
    }
}
