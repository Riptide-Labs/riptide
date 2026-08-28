/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.OptionListener.Verdict;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the fan-out can see that no single consumer can: what became of the whole record (#599).
 *
 * <p>Every other meter in the parser namespace counts what one consumer did. None counted the
 * verdict of the stream, which is why #598 — an exporter advertising 1:100 sampling in a scope
 * riptide dropped, a hundredfold undercount — was found by reading that exporter's source rather
 * than from any signal riptide produced.</p>
 *
 * <p><b>Why three states and not two.</b> Collapsing "nobody recognised it" into "nobody claimed
 * it" inverts the signal on a real fleet. An exporter sending interface option records with no
 * usable ifIndex would drive a single counter up forever on records riptide understood perfectly,
 * while a filter-ratio advertisement riptide knowingly discards (#596) would leave it flat — a gap
 * reported where there is none and silence where there is one.</p>
 */
class OptionListenerTest {

    private static final String OFFERED = MetricRegistry.name("parser", "options", "offered");
    private static final String CLAIMED = MetricRegistry.name("parser", "options", "claimed");
    private static final String UNUSABLE = MetricRegistry.name("parser", "options", "recognisedUnusable");
    private static final String UNRECOGNISED = MetricRegistry.name("parser", "options", "unrecognised");

    /** A listener that answers on command, recording everything it was handed. */
    private static final class Fake implements OptionListener {
        private final Verdict verdict;
        private final List<ExporterIdentity> identities = new ArrayList<>();
        private final List<Collection<Value<?>>> scopes = new ArrayList<>();
        private final List<List<Value<?>>> values = new ArrayList<>();

        Fake(final Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public Verdict accept(final ExporterIdentity identity, final Collection<Value<?>> scopes,
                final List<Value<?>> values) {
            this.identities.add(identity);
            this.scopes.add(scopes);
            this.values.add(values);
            return this.verdict;
        }
    }

    private static long count(final MetricRegistry metrics, final String name) {
        return metrics.meter(name).getCount();
    }

    private static ExporterIdentity anExporter() throws Exception {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.0.2.9"), 7);
    }

    private static final List<Value<?>> SCOPES = List.of(new UnsignedValue("SCOPE:SYSTEM", 1));
    private static final List<Value<?>> VALUES = List.of(new UnsignedValue("IF_INDEX", 3));

    private static void offerOneRecord(final OptionListener listener) throws Exception {
        listener.accept(anExporter(), SCOPES, VALUES);
    }

    /** A shape riptide was never taught: routine, and counted apart from a real gap. */
    @Test
    void aRecordNobodyRecognisesCountsAsUnrecognised() throws Exception {
        final var metrics = new MetricRegistry();
        offerOneRecord(OptionListener.of(metrics,
                new Fake(Verdict.UNRECOGNISED), new Fake(Verdict.UNRECOGNISED)));

        assertThat(count(metrics, UNRECOGNISED)).isEqualTo(1);
        assertThat(count(metrics, UNUSABLE)).isZero();
        assertThat(count(metrics, CLAIMED)).isZero();
    }

    /**
     * The state #598 was: understood, and served nothing from. This is the one worth an alert.
     *
     * <p>It outranks {@code UNRECOGNISED} on purpose. A record one consumer recognised is not a
     * shape riptide has never seen, whatever the other consumer made of it.</p>
     */
    @Test
    void aRecordSomeoneRecognisedButCouldNotUseCountsSeparately() throws Exception {
        final var metrics = new MetricRegistry();
        offerOneRecord(OptionListener.of(metrics,
                new Fake(Verdict.UNRECOGNISED), new Fake(Verdict.RECOGNISED_BUT_UNUSABLE)));

        assertThat(count(metrics, UNUSABLE)).isEqualTo(1);
        assertThat(count(metrics, UNRECOGNISED))
                .as("recognised-but-unusable must not be filed among the shapes nobody knows")
                .isZero();
    }

    /** A decline is not an absence of claims — the guard that gives these meters their meaning. */
    @Test
    void aRecordOneConsumerClaimsIsNotCountedAsAGap() throws Exception {
        final var metrics = new MetricRegistry();
        final var declining = new Fake(Verdict.RECOGNISED_BUT_UNUSABLE);
        offerOneRecord(OptionListener.of(metrics, declining, new Fake(Verdict.CLAIMED)));

        assertThat(count(metrics, CLAIMED)).isEqualTo(1);
        assertThat(count(metrics, UNUSABLE)).isZero();
        assertThat(count(metrics, UNRECOGNISED)).isZero();
        assertThat(declining.identities)
                .as("the declining consumer must still have been offered the record")
                .hasSize(1);
    }

    /**
     * The same with the claiming consumer first, because order must not decide the answer.
     *
     * <p>Not redundant: a fan-out keeping only the last consumer's verdict passes the test above,
     * whose claiming consumer runs last. A mutation replacing the combination with an assignment
     * survived the suite until this existed.</p>
     */
    @Test
    void aClaimIsNotErasedByALaterDecline() throws Exception {
        final var metrics = new MetricRegistry();
        offerOneRecord(OptionListener.of(metrics,
                new Fake(Verdict.CLAIMED), new Fake(Verdict.UNRECOGNISED)));

        assertThat(count(metrics, CLAIMED)).isEqualTo(1);
        assertThat(count(metrics, UNRECOGNISED)).isZero();
    }

    /** Every consumer sees every record: one record can carry an interface name and a rate. */
    @Test
    void everyConsumerIsOfferedEveryRecordWithItsArgumentsIntact() throws Exception {
        final var metrics = new MetricRegistry();
        final var first = new Fake(Verdict.CLAIMED);
        final var second = new Fake(Verdict.CLAIMED);
        final var exporter = anExporter();

        OptionListener.of(metrics, first, second).accept(exporter, SCOPES, VALUES);

        for (final Fake fake : List.of(first, second)) {
            // Not just "was called": a fan-out forwarding constants would pass that.
            assertThat(fake.identities).containsExactly(exporter);
            assertThat(fake.scopes).containsExactly(SCOPES);
            assertThat(fake.values).containsExactly(VALUES);
        }
    }

    /**
     * The three outcomes are exhaustive, so the denominator equals their sum.
     *
     * <p>The invariant is the guard: a branch that forgets to report a verdict, or a fourth state
     * added later and not counted, breaks this rather than quietly under-reporting.</p>
     */
    @Test
    void offeredEqualsTheSumOfTheThreeOutcomes() throws Exception {
        final var metrics = new MetricRegistry();
        final var listener = OptionListener.of(metrics,
                new Fake(Verdict.CLAIMED), new Fake(Verdict.UNRECOGNISED));
        final var gaps = OptionListener.of(metrics, new Fake(Verdict.RECOGNISED_BUT_UNUSABLE));
        final var unknown = OptionListener.of(metrics, new Fake(Verdict.UNRECOGNISED));

        offerOneRecord(listener);
        offerOneRecord(gaps);
        offerOneRecord(unknown);

        assertThat(count(metrics, OFFERED))
                .isEqualTo(count(metrics, CLAIMED) + count(metrics, UNUSABLE)
                        + count(metrics, UNRECOGNISED))
                .isEqualTo(3);
    }

    /** The meters exist from startup, so a panel or absence rule can be built on them. */
    @Test
    void theMetersAreRegisteredBeforeAnyRecordArrives() {
        final var metrics = new MetricRegistry();
        OptionListener.of(metrics, new Fake(Verdict.CLAIMED));

        assertThat(metrics.getMeters().keySet())
                .as("a lazily registered meter appears in /metrics only after the first record,"
                        + " which breaks any absence rule built on it")
                .contains(OFFERED, CLAIMED, UNUSABLE, UNRECOGNISED);
    }

    /** With no consumers, nothing recognised anything. */
    @Test
    void withNoConsumersEveryRecordIsUnrecognised() throws Exception {
        final var metrics = new MetricRegistry();
        final var listener = OptionListener.of(metrics);

        offerOneRecord(listener);
        offerOneRecord(listener);

        assertThat(count(metrics, UNRECOGNISED)).isEqualTo(2);
        assertThat(count(metrics, OFFERED)).isEqualTo(2);
    }

    /** The no-op listener says what it did rather than returning silently. */
    @Test
    void theNoOpListenerReportsUnrecognised() throws Exception {
        assertThat(OptionListener.NONE.accept(anExporter(), SCOPES, VALUES))
                .isEqualTo(Verdict.UNRECOGNISED);
    }

    /** Combining is a maximum, in both directions — the rule the fan-out relies on. */
    @Test
    void theStrongerVerdictWinsWhicheverSideItIsOn() {
        assertThat(Verdict.UNRECOGNISED.or(Verdict.CLAIMED)).isEqualTo(Verdict.CLAIMED);
        assertThat(Verdict.CLAIMED.or(Verdict.UNRECOGNISED)).isEqualTo(Verdict.CLAIMED);
        assertThat(Verdict.UNRECOGNISED.or(Verdict.RECOGNISED_BUT_UNUSABLE))
                .isEqualTo(Verdict.RECOGNISED_BUT_UNUSABLE);
        assertThat(Verdict.RECOGNISED_BUT_UNUSABLE.or(Verdict.CLAIMED)).isEqualTo(Verdict.CLAIMED);
    }
}
