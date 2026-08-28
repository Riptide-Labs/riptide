/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.riptide.flows.parser.ie.Value;
import org.riptide.pipeline.ExporterIdentity;

import java.util.Collection;
import java.util.List;

/**
 * Observes option data records at arrival, with raw scopes and fields intact. This is
 * the only place `(exporter identity, ifIndex → interface name)` mappings exist in a
 * consumable shape: the per-record option merge can never carry interface options
 * (v9 scope names don't appear as data-record fields by construction; IPFIX matches
 * the ingress side at most), so consumers tap here instead.
 */
public interface OptionListener {

    /**
     * What a consumer did with one option data record.
     *
     * <p>Three states rather than two, because two collapse the only distinction that matters
     * (#599). A record no consumer <em>recognised</em> is a shape riptide was never taught — a VRF
     * or application table, routine on real exporters and nobody's defect. A record a consumer
     * recognised and could take nothing from is a rate riptide was offered and dropped, which is
     * what #598 was: softflowd advertising 1:100 in a scope riptide discarded, a hundredfold
     * undercount found by reading that exporter's source rather than from any signal riptide
     * produced.</p>
     *
     * <p>Ordered weakest to strongest, so combining verdicts is a maximum: a claim by any consumer
     * outranks a decline by any other, in either order.</p>
     */
    enum Verdict {
        /** Nobody's record: no field this consumer knows appeared in it. */
        UNRECOGNISED,
        /** This consumer's kind of record, but it held nothing servable. */
        RECOGNISED_BUT_UNUSABLE,
        /** Taken: something was stored that this consumer will serve later. */
        CLAIMED;

        /** The stronger of two verdicts — a claim anywhere beats a decline everywhere. */
        Verdict or(final Verdict other) {
            return compareTo(other) >= 0 ? this : other;
        }
    }

    OptionListener NONE = (identity, scopes, values) -> Verdict.UNRECOGNISED;

    /**
     * Offers one option data record to this consumer.
     *
     * @return what this consumer did with it. Returning {@link Verdict#RECOGNISED_BUT_UNUSABLE}
     *     rather than {@link Verdict#CLAIMED} for a record it understood but stored nothing from is
     *     the whole point: "I looked" is a different fact from "I took", and only the first is
     *     evidence that riptide is dropping something an exporter meant it to have.
     */
    Verdict accept(ExporterIdentity identity, Collection<Value<?>> scopes, List<Value<?>> values);

    /**
     * Fans one tap out to several consumers, counting what became of each record (#599).
     *
     * <p>Option records are a shared stream — one table reads interface names out of it, another
     * sampling rates — and each consumer ignores what it does not recognize. What no consumer could
     * see is the verdict of the <em>whole</em> stream: every other meter in the parser namespace
     * counts what one consumer did, so a record everybody shrugged at was discarded with no counter,
     * no log and no trace.</p>
     *
     * <p>Four meters, and the split is load-bearing rather than tidy. Collapsing the two gap states
     * into one inverts the signal on real fleets: an exporter sending interface option records with
     * no usable ifIndex would drive a single counter up forever on records riptide recognised
     * perfectly, while a filter-ratio advertisement riptide knowingly discards (#596) would leave it
     * flat — a gap reported where there is none, and silence where there is one.</p>
     *
     * <p>{@code offered} is the denominator the other three are unreadable without, and it is also a
     * guard: the three outcomes are exhaustive, so {@code offered} must equal their sum, and a
     * branch that forgets to report a verdict breaks that rather than hiding.</p>
     */
    static OptionListener of(final MetricRegistry metrics, final OptionListener... listeners) {
        final List<OptionListener> targets = List.of(listeners);
        final Meter offered = metrics.meter(MetricRegistry.name("parser", "options", "offered"));
        final Meter claimed = metrics.meter(MetricRegistry.name("parser", "options", "claimed"));
        final Meter unusable = metrics.meter(MetricRegistry.name("parser", "options", "recognisedUnusable"));
        final Meter unrecognised = metrics.meter(MetricRegistry.name("parser", "options", "unrecognised"));
        return (identity, scopes, values) -> {
            Verdict verdict = Verdict.UNRECOGNISED;
            for (final OptionListener target : targets) {
                // Every consumer is offered the record even once one has claimed it: a record can
                // carry an interface name and a sampling rate at once, and short-circuiting would
                // silently stop the second consumer seeing records the first happened to want.
                verdict = verdict.or(target.accept(identity, scopes, values));
            }
            offered.mark();
            // A switch expression, so a fourth Verdict fails to compile here rather than being
            // counted in `offered` alone.
            final Meter outcome = switch (verdict) {
                case CLAIMED -> claimed;
                case RECOGNISED_BUT_UNUSABLE -> unusable;
                case UNRECOGNISED -> unrecognised;
            };
            outcome.mark();
            return verdict;
        };
    }
}
