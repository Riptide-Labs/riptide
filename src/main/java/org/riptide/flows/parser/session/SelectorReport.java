/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import java.util.function.Function;

/**
 * The selection ratio stated by an IPFIX Selector Report.
 *
 * <p>RFC 5476 §6.5.2 requires an exporter to describe every Selector it runs in an Options Template
 * Record scoped by {@code selectorId}, carrying {@code selectorAlgorithm} and that algorithm's own
 * parameters. This turns those parameters into the multiplier a collector applies to the counters
 * the Selector produced.</p>
 *
 * <p><b>Absent parameters yield no rate.</b> Every branch returns {@code null} unless the exporter
 * stated everything the formula needs. The predecessor to this class defaulted each missing input
 * to the value that makes its formula evaluate to {@code 1.0} — {@code interval → 1}, {@code
 * spacing → 0}, {@code probability → 1}, the hash ranges to their full span — so an exporter naming
 * an algorithm and omitting its parameters was recorded as not sampling. A default that always
 * produces an answer turns "the exporter said nothing" into "riptide computed 1.0", which is a
 * different claim entirely (#584).</p>
 *
 * <p><b>Only algorithms that express a ratio produce one.</b> Property match filtering (5) reports
 * match criteria rather than a ratio — RFC 5476 §6.5.2.5 describes "a mix of information from the
 * packet and information from the router" — so no multiplier recovers what it discarded.
 * Flow-state dependent selection (9) states no parameters at all, and 0 is unassigned. Each falls
 * through rather than resolving.</p>
 *
 * <p>The formula for the systematic algorithms matches nfdump ({@code intervalTotal /
 * packetInterval}) and Akvorado ({@code (packetInterval + packetSpace) / packetInterval}), which
 * are the only two collectors surveyed that compute one at all.</p>
 */
public final class SelectorReport {

    private SelectorReport() {
    }

    /**
     * The multiplier this Selector's parameters imply, or {@code null} where the report states none.
     *
     * @param algorithm the {@code selectorAlgorithm} (IE 304) value the report carries
     * @param field     reads a parameter by its IANA name, returning {@code null} when absent
     */
    public static Double rate(final int algorithm, final Function<String, Double> field) {
        return switch (algorithm) {
            // 1 selects `interval` packets in a row, then skips `space` of them; 2 does the same in
            // microseconds rather than packets. They take different Information Elements, which is
            // why they are separate branches: reading a first-of pair across both conflates a
            // packet count with a duration.
            case 1 -> systematic(field.apply("samplingPacketInterval"), field.apply("samplingPacketSpace"));
            case 2 -> systematic(field.apply("samplingTimeInterval"), field.apply("samplingTimeSpace"));
            case 3 -> outOf(field.apply("samplingPopulation"), field.apply("samplingSize"));
            case 4 -> probability(field.apply("samplingProbability"));
            // 6, 7 and 8 are BOB, IPSX and CRC hash-based filtering. They differ only in the hash
            // function, and all three report the same ranges, so the selected fraction of the hash
            // space is a genuine selection ratio for each. 8 previously sat with the algorithms
            // that express nothing, and was refused a ratio it does state.
            case 6, 7, 8 -> hash(field.apply("hashOutputRangeMin"), field.apply("hashOutputRangeMax"),
                    field.apply("hashSelectedRangeMin"), field.apply("hashSelectedRangeMax"));
            // 0 unassigned, 5 property match filtering, 9 flow-state dependent: no ratio exists.
            // Listed rather than left to the default so that adding an algorithm to the registry
            // does not silently join them.
            case 0, 5, 9 -> null;
            default -> null;
        };
    }

    /** Systematic count-based (1) and time-based (2) selection: {@code (interval + spacing) / interval}. */
    private static Double systematic(final Double interval, final Double spacing) {
        if (interval == null || spacing == null || interval <= 0.0) {
            return null;
        }
        return (interval + spacing) / interval;
    }

    /** Random n-out-of-N (3): {@code N / n}. */
    private static Double outOf(final Double population, final Double size) {
        if (population == null || size == null || size <= 0.0) {
            return null;
        }
        return population / size;
    }

    /** Uniform probabilistic (4): the reciprocal of the selection probability. */
    private static Double probability(final Double probability) {
        if (probability == null || probability <= 0.0 || probability > 1.0) {
            return null;
        }
        return 1.0 / probability;
    }

    /**
     * Hash-based filtering (6, 7, 8): the share of the hash function's output range that the
     * selected range covers.
     *
     * <p><b>The bounds are inclusive, so a range spans {@code max - min + 1} values.</b> RFC 5477
     * describes them only as "the value for the beginning" and "the value for the end", but RFC 5475
     * §7 settles it: "if the selection interval specification is [1:3], [6:9] all packets are
     * selected for which the hash result is 1,2,3,6,7,8, or 9" — seven values from two intervals
     * whose endpoint differences sum to five.</p>
     *
     * <p>Computing {@code max - min} is wrong twice over. It understates every ratio, and it makes a
     * single selected bucket — {@code min == max}, which is exactly how 1-in-N filtering over an
     * N-value output range is expressed — collapse to zero and be discarded as degenerate. An
     * exporter selecting bucket 0 of 1024 would have had its report dropped and its flows
     * under-counted a thousandfold. The predecessor to this class had the same error.</p>
     *
     * <p>RFC 5477 allows more than one selected range to be reported. Only the first pair is read
     * here, so an exporter selecting several disjoint ranges is under-counted rather than
     * mis-counted. No exporter sending any of these has been observed, so a multi-range
     * implementation would have nothing to verify it against.</p>
     */
    private static Double hash(final Double outputMin, final Double outputMax,
                               final Double selectedMin, final Double selectedMax) {
        if (outputMin == null || outputMax == null || selectedMin == null || selectedMax == null) {
            return null;
        }
        final double selected = selectedMax - selectedMin + 1.0;
        final double output = outputMax - outputMin + 1.0;
        // an inverted range is malformed rather than degenerate, and selecting more than the whole
        // output range is not expressible either; both mean the report cannot be read
        if (selected <= 0.0 || output <= 0.0 || selected > output) {
            return null;
        }
        return output / selected;
    }
}
