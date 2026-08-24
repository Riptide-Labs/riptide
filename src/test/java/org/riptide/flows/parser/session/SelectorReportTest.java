/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every value in the IE 304 registry, and what each one may claim about a selection ratio.
 *
 * <p>All ten are covered deliberately. The predecessor to this branch exercised two of them (3 and
 * 4) and shipped four defects across the rest: 1 and 2 read the flow-selection registry's elements
 * instead of the packet-selection ones, 8 was refused a ratio it does state, and 5 was computed a
 * ratio it cannot have. A switch tested at two of ten points is a switch nobody has read (#584).</p>
 */
class SelectorReportTest {

    /**
     * A report stating the named parameters and nothing else.
     *
     * <p>The pairing is checked rather than assumed. Reading {@code i + 1} under a bare
     * {@code i < length} bound walks off the end of an odd-length argument list, which would turn a
     * mistyped call into an {@code ArrayIndexOutOfBoundsException} rather than a readable failure —
     * and would silently drop the trailing name if the bound were simply tightened.</p>
     */
    private static Double rate(final int algorithm, final Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "each parameter name needs a value; got " + namesAndValues.length + " arguments");
        }
        final Map<String, Double> fields = new HashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], ((Number) namesAndValues[i + 1]).doubleValue());
        }
        return SelectorReport.rate(algorithm, fields::get);
    }

    // ---- the algorithms that express a ratio -------------------------------------------------

    /**
     * Systematic count-based: select {@code interval} packets in a row, skip {@code space}.
     *
     * <p>{@code (1 + 99) / 1 = 100}, which is the expression nfdump and Akvorado both use. riptide
     * computed {@code interval + space / interval}, which agrees only when {@code interval} is 1
     * and is why the error survived: the common case hides it.</p>
     */
    @Test
    void systematicCountBasedDividesTheWholeCycleByTheSelectedPart() {
        assertThat(rate(1, "samplingPacketInterval", 1, "samplingPacketSpace", 99)).isEqualTo(100.0);
        // the case that separates the two expressions: 2 selected out of every 10
        assertThat(rate(1, "samplingPacketInterval", 2, "samplingPacketSpace", 8)).isEqualTo(5.0);
    }

    /** Systematic time-based reads its own pair of elements, in microseconds rather than packets. */
    @Test
    void systematicTimeBasedReadsTheTimeElementsNotThePacketOnes() {
        assertThat(rate(2, "samplingTimeInterval", 1, "samplingTimeSpace", 99)).isEqualTo(100.0);
        // 1 and 2 are different algorithms taking different elements; neither reads the other's
        assertThat(rate(2, "samplingPacketInterval", 1, "samplingPacketSpace", 99)).isNull();
        assertThat(rate(1, "samplingTimeInterval", 1, "samplingTimeSpace", 99)).isNull();
    }

    @Test
    void randomNOutOfNIsThePopulationOverTheSample() {
        assertThat(rate(3, "samplingPopulation", 1000, "samplingSize", 1)).isEqualTo(1000.0);
        assertThat(rate(3, "samplingPopulation", 1000, "samplingSize", 4)).isEqualTo(250.0);
    }

    @Test
    void uniformProbabilisticIsTheReciprocalOfTheProbability() {
        assertThat(rate(4, "samplingProbability", 0.001)).isEqualTo(1000.0);
        assertThat(rate(4, "samplingProbability", 1.0)).isEqualTo(1.0);
    }

    /**
     * All three hash functions state the same ranges, so all three yield a ratio.
     *
     * <p>8 (CRC) used to sit with the algorithms expressing nothing, and was refused the ratio it
     * states while 5 — which states none — was computed one. The two were on opposite wrong sides
     * of the same boundary.</p>
     */
    @Test
    void everyHashBasedFilterYieldsTheSelectedShareOfTheOutputRange() {
        for (final int algorithm : new int[]{6, 7, 8}) {
            // a 10-bit hash: output range [0,1023] is 1024 values, selected [0,63] is 64
            assertThat(rate(algorithm,
                    "hashOutputRangeMin", 0, "hashOutputRangeMax", 1023,
                    "hashSelectedRangeMin", 0, "hashSelectedRangeMax", 63))
                    .describedAs("selectorAlgorithm %d", algorithm)
                    .isEqualTo(16.0);
        }
    }

    /**
     * The bounds are inclusive, so one selected bucket out of 1024 is a rate of 1024.
     *
     * <p>This is how 1-in-N hash filtering is expressed, and computing the span as
     * {@code max - min} makes it collapse to zero and be thrown away as degenerate. RFC 5475 §7:
     * the interval {@code [1:3]} selects hash results 1, 2 and 3.</p>
     */
    @Test
    void aSingleSelectedBucketIsARangeOfOneNotZero() {
        assertThat(rate(6,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 1023,
                "hashSelectedRangeMin", 0, "hashSelectedRangeMax", 0)).isEqualTo(1024.0);
        // and the RFC's own worked example: [1:3] out of [0:9] is 3 of 10
        assertThat(rate(6,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 9,
                "hashSelectedRangeMin", 1, "hashSelectedRangeMax", 3))
                .isEqualTo(10.0 / 3.0);
    }

    /** Selecting the whole output range is "not filtering", which is a rate of 1. */
    @Test
    void selectingTheWholeOutputRangeIsARateOfOne() {
        assertThat(rate(6,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 1023,
                "hashSelectedRangeMin", 0, "hashSelectedRangeMax", 1023)).isEqualTo(1.0);
    }

    // ---- the algorithms that express none ----------------------------------------------------

    /**
     * Property match filtering states match criteria, not a ratio.
     *
     * <p>RFC 5476 §6.5.2.5 describes its report as "a mix of information from the packet and
     * information from the router". No multiplier recovers what a filter discarded, so this must
     * yield nothing even when hash ranges are present — which is how it used to reach the hash
     * branch and return a fabricated 1.0.</p>
     */
    @Test
    void propertyMatchFilteringNeverYieldsARate() {
        assertThat(rate(5)).isNull();
        assertThat(rate(5,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 1024,
                "hashSelectedRangeMin", 0, "hashSelectedRangeMax", 64)).isNull();
    }

    @Test
    void unassignedAndFlowStateDependentYieldNothing() {
        assertThat(rate(0)).isNull();
        assertThat(rate(9)).isNull();
    }

    @Test
    void anAlgorithmOutsideTheRegistryYieldsNothing() {
        assertThat(rate(10)).isNull();
        assertThat(rate(255)).isNull();
    }

    // ---- absent and degenerate parameters ----------------------------------------------------

    /**
     * The defect this class exists to prevent: a stated algorithm with no parameters.
     *
     * <p>Every branch used to default its inputs to the values that make its own formula evaluate
     * to {@code 1.0}. That is accepted as a rate — 1 legitimately means "not sampling" — and sat
     * above the exporter's own advertisement, so a bare algorithm reported the exporter unsampled
     * and suppressed the real rate it had advertised.</p>
     */
    @Test
    void everyRatioAlgorithmYieldsNothingWhenItsParametersAreAbsent() {
        for (final int algorithm : new int[]{1, 2, 3, 4, 6, 7, 8}) {
            assertThat(rate(algorithm))
                    .describedAs("selectorAlgorithm %d with no parameters", algorithm)
                    .isNull();
        }
    }

    /** Half a parameter set is no parameter set. */
    @Test
    void aPartiallyStatedAlgorithmYieldsNothing() {
        assertThat(rate(1, "samplingPacketInterval", 1)).isNull();
        assertThat(rate(1, "samplingPacketSpace", 99)).isNull();
        assertThat(rate(3, "samplingPopulation", 1000)).isNull();
        assertThat(rate(6, "hashOutputRangeMin", 0, "hashOutputRangeMax", 1024)).isNull();
    }

    /** A degenerate range or a zero divisor yields nothing rather than an infinity or a throw. */
    @Test
    void degenerateParametersYieldNothing() {
        assertThat(rate(1, "samplingPacketInterval", 0, "samplingPacketSpace", 99)).isNull();
        assertThat(rate(3, "samplingPopulation", 1000, "samplingSize", 0)).isNull();
        assertThat(rate(4, "samplingProbability", 0.0)).isNull();
        // inverted: the end of the range before its beginning
        assertThat(rate(6,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 1023,
                "hashSelectedRangeMin", 64, "hashSelectedRangeMax", 32)).isNull();
        // selecting more than the whole output range is not expressible either
        assertThat(rate(6,
                "hashOutputRangeMin", 0, "hashOutputRangeMax", 63,
                "hashSelectedRangeMin", 0, "hashSelectedRangeMax", 1023)).isNull();
    }

    /** A probability above 1 is not a probability, and its reciprocal would be below 1. */
    @Test
    void anImpossibleProbabilityYieldsNothing() {
        assertThat(rate(4, "samplingProbability", 1.5)).isNull();
    }
}
