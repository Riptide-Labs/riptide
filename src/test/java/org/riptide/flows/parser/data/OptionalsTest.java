/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalsTest {

    @Test
    void earlierCandidateWinsOverLaterOne() {
        assertThat(Optionals.first(5, 7)).contains(5);
    }

    @Test
    void nullCandidatesAreSkipped() {
        assertThat(Optionals.first(null, null, 7)).contains(7);
    }

    @Test
    void allCandidatesNullIsEmpty() {
        assertThat(Optionals.first((Integer) null, null)).isEmpty();
    }

    @Test
    void noCandidatesIsEmpty() {
        assertThat(Optionals.<Integer>first()).isEmpty();
    }
}
