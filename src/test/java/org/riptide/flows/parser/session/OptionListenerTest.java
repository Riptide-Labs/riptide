/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.pipeline.ExporterIdentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the fan-out can see that no single consumer can: a record nobody claimed (#599).
 *
 * <p>Every meter in the parser namespace counts what a consumer <em>did</em>. None counted what
 * arrived and was claimed by no one, which is why #598 — an exporter advertising 1:100 sampling in
 * a scope riptide dropped, a hundredfold undercount — was found by reading that exporter's source
 * rather than from any signal riptide produced.</p>
 *
 * <p>The load-bearing test here is {@link #aRecordOneConsumerClaimsIsNotCounted()}. A counter wired
 * to a single consumer's decline would pass every other assertion in this class while answering a
 * different question, and would go on reporting a gap for records another consumer is happily
 * serving.</p>
 */
class OptionListenerTest {

    private static final String UNCLAIMED = MetricRegistry.name("parser", "options", "unclaimed");

    /** A listener that claims or declines on command, recording what it was offered. */
    private static final class Consumer implements OptionListener {
        private final boolean claims;
        private final List<ExporterIdentity> offered = new ArrayList<>();

        Consumer(final boolean claims) {
            this.claims = claims;
        }

        @Override
        public boolean accept(final ExporterIdentity identity, final Collection<Value<?>> scopes,
                final List<Value<?>> values) {
            this.offered.add(identity);
            return this.claims;
        }
    }

    private static long unclaimed(final MetricRegistry metrics) {
        return metrics.meter(UNCLAIMED).getCount();
    }

    private static void offerOneRecord(final OptionListener listener) {
        listener.accept(null, List.of(), List.of());
    }

    /** The case #599 exists for: nobody took it, and now something says so. */
    @Test
    void aRecordNoConsumerClaimsIsCounted() {
        final var metrics = new MetricRegistry();
        final var listener = OptionListener.of(metrics, new Consumer(false), new Consumer(false));

        offerOneRecord(listener);

        assertThat(unclaimed(metrics)).isEqualTo(1);
    }

    /**
     * A decline is not an absence of claims.
     *
     * <p>The guard that gives this counter its meaning. One consumer declining is a claim somewhere
     * else far more often than it is a gap — softflowd's record is exactly that today, declined by
     * the interface table and claimed by sampling since #604 — so a counter that merely mirrored a
     * per-consumer {@code skipped} would report a gap that is not there.</p>
     */
    @Test
    void aRecordOneConsumerClaimsIsNotCounted() {
        final var metrics = new MetricRegistry();
        final var declining = new Consumer(false);
        final var claiming = new Consumer(true);
        final var listener = OptionListener.of(metrics, declining, claiming);

        offerOneRecord(listener);

        assertThat(unclaimed(metrics))
                .as("one consumer declining is not 'claimed by nobody'")
                .isZero();
        assertThat(declining.offered)
                .as("the declining consumer must still have been offered the record, or the fan-out"
                        + " is short-circuiting and this proves nothing")
                .hasSize(1);
    }

    /**
     * The same, with the claiming consumer <em>first</em> — because order must not decide the answer.
     *
     * <p>Not redundant with the test above, and this is worth stating: a fan-out that kept only the
     * last consumer's verdict passes that one, because its claiming consumer happens to run last. It
     * fails this one. A mutation replacing the accumulation with a plain assignment survived the
     * suite until this existed.</p>
     */
    @Test
    void aRecordTheFirstConsumerClaimsIsNotCounted() {
        final var metrics = new MetricRegistry();
        final var listener = OptionListener.of(metrics, new Consumer(true), new Consumer(false));

        offerOneRecord(listener);

        assertThat(unclaimed(metrics))
                .as("a claim by an earlier consumer must not be forgotten by a later decline")
                .isZero();
    }

    /** Both claiming is still not nobody, and both must still be offered it. */
    @Test
    void aRecordBothConsumersClaimIsNotCountedAndReachesBoth() {
        final var metrics = new MetricRegistry();
        final var first = new Consumer(true);
        final var second = new Consumer(true);
        final var listener = OptionListener.of(metrics, first, second);

        offerOneRecord(listener);

        assertThat(unclaimed(metrics)).isZero();
        // A record can carry an interface name and a sampling rate at once; short-circuiting on the
        // first claim would silently stop the second consumer ever seeing those records.
        assertThat(first.offered).hasSize(1);
        assertThat(second.offered).hasSize(1);
    }

    /** The fan-out reports its own verdict too, so fan-outs can nest without losing the answer. */
    @Test
    void theFanOutReportsWhetherAnyoneClaimed() {
        final var metrics = new MetricRegistry();

        assertThat(OptionListener.of(metrics, new Consumer(false)).accept(null, List.of(), List.of()))
                .isFalse();
        assertThat(OptionListener.of(metrics, new Consumer(true)).accept(null, List.of(), List.of()))
                .isTrue();
    }

    /** With no consumers at all, every record is unclaimed — the degenerate case, stated. */
    @Test
    void withNoConsumersEveryRecordIsUnclaimed() {
        final var metrics = new MetricRegistry();
        final var listener = OptionListener.of(metrics);

        offerOneRecord(listener);
        offerOneRecord(listener);

        assertThat(unclaimed(metrics)).isEqualTo(2);
    }

    /** {@link OptionListener#NONE} claims nothing, and says so rather than returning silently. */
    @Test
    void theNoOpListenerClaimsNothing() {
        assertThat(OptionListener.NONE.accept(null, List.of(), List.of())).isFalse();
    }
}
