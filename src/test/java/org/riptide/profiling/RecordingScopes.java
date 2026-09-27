/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import io.pyroscope.labels.v2.LabelsSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Stands in for the profiler's scopes in tests: records which labels are active on the calling thread,
 * because the agent offers no way to read that back.
 */
public final class RecordingScopes implements ProfilingLabels.Scopes {

    private final ThreadLocal<List<String>> active = ThreadLocal.withInitial(ArrayList::new);
    private final Set<LabelsSet> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private int entered;

    @Override
    public synchronized ProfilingLabels.Scope enter(final LabelsSet labels) {
        this.entered++;
        this.seen.add(labels);
        final List<String> pairs = new ArrayList<>();
        labels.forEachLabel((name, value) -> pairs.add(name + "=" + value));
        this.active.get().addAll(pairs);
        return () -> this.active.get().removeAll(pairs);
    }

    /** Opens the profiling label gate with a fresh recorder, for tests outside this package. */
    public static RecordingScopes install() {
        final var recorder = new RecordingScopes();
        ProfilingLabels.enableWith(recorder);
        return recorder;
    }

    /** Shuts the gate again; call from {@code @AfterEach}. */
    public static void uninstall() {
        ProfilingLabels.disable();
    }

    /** The labels active on the calling thread, as {@code name=value}. */
    public List<String> active() {
        return List.copyOf(this.active.get());
    }

    public synchronized int entered() {
        return this.entered;
    }

    public synchronized int distinctLabelSets() {
        return this.seen.size();
    }
}
