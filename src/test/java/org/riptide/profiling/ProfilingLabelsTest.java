/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilingLabelsTest {

    private final RecordingScopes recorder = new RecordingScopes();

    @AfterEach
    void closeTheGate() {
        ProfilingLabels.disable();
    }

    @Test
    void whileProfilingIsOffNoScopeIsEntered() {
        final var listener = ProfilingLabels.component("listener", "flows");

        try (ProfilingLabels.Scope ignored = listener.enter()) {
            assertThat(this.recorder.active()).isEmpty();
        }

        assertThat(this.recorder.entered()).isZero();
    }

    @Test
    void onceOpenTheWorkRunsUnderItsStageAndComponent() {
        final var listener = ProfilingLabels.component("listener", "flows");
        ProfilingLabels.enableWith(this.recorder);

        try (ProfilingLabels.Scope ignored = listener.enter()) {
            assertThat(this.recorder.active()).containsExactly("stage=listener", "component=flows");
        }

        assertThat(this.recorder.active()).as("the scope is left on close").isEmpty();
    }

    @Test
    void theLabelSetIsBuiltOncePerComponent() {
        final var flusher = ProfilingLabels.component("batch-writer", "flusher");
        ProfilingLabels.enableWith(this.recorder);

        try (ProfilingLabels.Scope ignored = flusher.enter()) {
            // nothing to do: only the label set handed to the scope matters
        }
        try (ProfilingLabels.Scope ignored = flusher.enter()) {
            // a second unit of work on the same component
        }

        assertThat(this.recorder.distinctLabelSets()).as("one label set, reused per unit of work").isEqualTo(1);
    }

    @Test
    void aComponentMadeBeforeTheGateOpensIsLabelledAfterIt() {
        // components are built when the pipeline starts, which can precede the agent starting
        final var parser = ProfilingLabels.component("parser-dispatch", "flows:netflow9");
        ProfilingLabels.enableWith(this.recorder);

        try (ProfilingLabels.Scope ignored = parser.enter()) {
            assertThat(this.recorder.active()).containsExactly("stage=parser-dispatch", "component=flows:netflow9");
        }
    }
}
