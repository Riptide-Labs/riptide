/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import io.pyroscope.labels.v2.LabelsSet;
import io.pyroscope.labels.v2.ScopedContext;

import java.util.Objects;

/**
 * Labels a pipeline component's work in the continuous profile with {@code stage} and
 * {@code component}, the names the golden-signals series use, so a stage's profile can be selected
 * on its own.
 *
 * <p>The only class that knows the profiler's label API. A component asks for its labels once, when it
 * is built, and brackets each unit of work with {@link Component#enter()}. Until the agent has started,
 * the gate is shut and {@code enter()} returns a shared no-op: a deployment without profiling pays one
 * volatile read per unit of work and enters no scope.
 */
public final class ProfilingLabels {

    /** A scope that is left on close. Declares no checked exception, so it fits any try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** How a scope is entered; the profiler's in production, a recorder in tests. */
    @FunctionalInterface
    interface Scopes {
        Scope enter(LabelsSet labels);
    }

    static final Scope NONE = () -> { };

    private static final Scopes PROFILER = labels -> {
        final ScopedContext context = new ScopedContext(labels);
        return context::close;
    };

    /** Null while profiling is off: the gate. */
    private static volatile Scopes scopes;

    private ProfilingLabels() {
    }

    /** Opens the gate; called once the profiling agent has started. */
    static void enable() {
        enableWith(PROFILER);
    }

    static void enableWith(final Scopes with) {
        scopes = Objects.requireNonNull(with);
    }

    static void disable() {
        scopes = null;
    }

    /** The labels for one pipeline component; build it once, when the component is built. */
    public static Component component(final String stage, final String component) {
        return new Component(new LabelsSet("stage", Objects.requireNonNull(stage),
                "component", Objects.requireNonNull(component)));
    }

    /** One component's labels, reused for every unit of work it does. */
    public static final class Component {
        private final LabelsSet labels;

        private Component(final LabelsSet labels) {
            this.labels = labels;
        }

        /** Enters this component's scope, or does nothing while profiling is off. */
        public Scope enter() {
            final Scopes with = scopes;
            return with == null ? NONE : with.enter(this.labels);
        }
    }
}
