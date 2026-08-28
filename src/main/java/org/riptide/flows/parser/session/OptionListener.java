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

    OptionListener NONE = (identity, scopes, values) -> false;

    /**
     * Offers one option data record to this consumer.
     *
     * @return whether this consumer <em>claimed</em> the record — took something from it that it
     *     will serve later. A consumer that recognises the record but finds nothing usable in it
     *     has not claimed it: the point of the verdict is to tell a record somebody uses from a
     *     record everybody shrugs at, so "I looked" must not count as "I took".
     */
    boolean accept(ExporterIdentity identity, Collection<Value<?>> scopes, List<Value<?>> values);

    /**
     * Fans one tap out to several consumers, counting the records none of them claims (#599).
     *
     * <p>Option records are a shared stream — one table reads interface names out of it, another
     * sampling rates — and each consumer ignores the records it does not recognize. What no
     * consumer could see is the record <em>nobody</em> recognized: every meter in the parser
     * namespace counts what a consumer did, so a record claimed by no one was discarded with no
     * counter, no log and no trace.</p>
     *
     * <p>That is not hypothetical. #598 was an exporter advertising 1:100 sampling in a scope
     * riptide dropped — a hundredfold undercount — and it was found by reading the exporter's
     * source, not by any signal riptide produced. This is the fan-out because the fan-out is the
     * only place that can see "nobody": a single consumer declining is a claim somewhere else more
     * often than it is a gap, so a per-consumer meter cannot answer this and must not be read as
     * if it could.</p>
     */
    static OptionListener of(final MetricRegistry metrics, final OptionListener... listeners) {
        final List<OptionListener> targets = List.of(listeners);
        final Meter unclaimed = metrics.meter(MetricRegistry.name("parser", "options", "unclaimed"));
        return (identity, scopes, values) -> {
            boolean claimed = false;
            for (final OptionListener target : targets) {
                // Every consumer is offered the record even once one has claimed it: a record can
                // legitimately carry both an interface name and a sampling rate, and short-circuiting
                // would silently stop the second consumer seeing records the first happened to want.
                claimed |= target.accept(identity, scopes, values);
            }
            if (!claimed) {
                unclaimed.mark();
            }
            return claimed;
        };
    }
}
