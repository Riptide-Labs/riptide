/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@link Nl6Container#ledger} really goes through {@link LedgerReading} (#662).
 *
 * <p>{@code LedgerReadingTest} pins the reading; this pins the delegate, which is otherwise reached
 * only by the e2e suites against a healthy nl6, where a delegate that had regressed to the raw read
 * behaves identically. No Docker is needed: on a container that was never started the raw read
 * throws from {@code getMappedPort}, so a bypassed delegate throws too, and a wrapped one answers
 * {@code 0} and holds it.</p>
 */
class Nl6ContainerTest {

    @Test
    void ledgerAbsorbsTheRawReadFailing() {
        final var nl6 = new Nl6Container();

        assertThat(nl6.ledger("netflow9"))
                .as("the raw read fails on an unstarted container; the delegate must hold 0, not throw")
                .isEqualTo(0L);
        assertThat(nl6.ledger("netflow9"))
                .as("and keep holding it, since a failed read contributes nothing")
                .isEqualTo(0L);
    }
}
