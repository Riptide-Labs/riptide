/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Sampling rates pushed by exporters as v9/IPFIX option records. A sampling exporter states its
 * rate once in a sampler options table and then omits it from every flow record, so without this
 * the collector records "not sampled" for a router sampling 1:1000.
 *
 * <p>Fed by the option tap ({@link OptionListener}) rather than the per-record option merge, for
 * the same reason {@code ExporterInterfaceTable} is. The sampler table is <em>System</em>-scoped
 * (verified against {@code netflow9_test_cisco_asr9k_opttpl257.dat}: scope {@code (1, 4)}, fields
 * 48/50/49/84). {@code lookupOptions} does synthesize a {@code SCOPE:SYSTEM} value for every data
 * record, so the scope <em>name</em> matches — but it synthesizes the observation domain, while a
 * real ASR9k writes its agent IP into that scope field. The keys never agree, so the merge cannot
 * deliver the record and the tap is the only path that reaches it.
 *
 * <p>Two record shapes feed this, kept in separate maps because an exporter may send both and a
 * single map would make the answer depend on arrival order:</p>
 *
 * <ul>
 *   <li><b>Sampler options</b> (IE 34/35, or v9's 48/49/50) state an interval outright. Keyed by
 *       {@link ExporterIdentity} alone, which already carries source address plus observation
 *       domain. Deliberately not keyed by {@code FLOW_SAMPLER_ID}: one rate per exporter and
 *       observation domain is the scope goflow2 and NetGauze both settled on, and a second key on
 *       a field many exporters omit from data records buys nothing.</li>
 *   <li><b>Selector Reports</b> (RFC 5476 §6.5.2) state an algorithm and its parameters, from which
 *       the rate is computed. Keyed additionally by {@code selectorId}, because that is what the
 *       RFC scopes them by and one exporter may run several Selectors at once.</li>
 * </ul>
 */
@Component
public class ExporterSamplingTable implements OptionListener {

    /**
     * The rate, under every name the two protocols use for it. Field 50 first: the ASR9k sampler
     * table carries {@code FLOW_SAMPLER_RANDOM_INTERVAL} and no field 34 at all, and goflow2
     * resolves in the same order for the same reason.
     */
    private static final List<String> INTERVAL_FIELDS = List.of(
            "FLOW_SAMPLER_RANDOM_INTERVAL", "samplerRandomInterval",
            "SAMPLING_INTERVAL", "samplingInterval");

    /**
     * The sampling mode from the same record. Carried with the rate so a flow resolving one from
     * the options table can resolve the other too, rather than reporting an interval next to
     * {@code Unassigned}.
     */
    private static final List<String> MODE_FIELDS = List.of(
            "FLOW_SAMPLER_MODE", "samplerMode",
            "SAMPLING_ALGORITHM", "samplingAlgorithm");

    /**
     * What an exporter advertised: the interval, the deprecated mode when it stated one, and the
     * RFC 5477 selector algorithm when the rate came from a Selector Report.
     *
     * <p>{@code mode} and {@code selectorAlgorithm} are different registries and MUST NOT be
     * conflated: {@code mode} is the deprecated IE 35 / IE 49 pair, where 1 is deterministic and 2
     * random, while {@code selectorAlgorithm} is IE 304, where 1 is systematic count-based and 2 is
     * systematic time-based. Reading one as the other renames the algorithm.</p>
     */
    public record AdvertisedRate(double interval, Integer mode, Integer selectorAlgorithm) {

        /**
         * A rate an exporter stated outright, which is the only kind NetFlow v9 has and the only
         * kind an IE 34/35 sampler options record carries.
         */
        public AdvertisedRate(final double interval, final Integer mode) {
            this(interval, mode, null);
        }

        /**
         * Whether riptide calculated this rate from a Selector Report's parameters rather than
         * reading it as stated. A Selector Report always names its algorithm and never states an
         * interval, so the presence of one is what distinguishes the two — and it is what separates
         * provenance {@code derived} from {@code options}.
         */
        public boolean computed() {
            return this.selectorAlgorithm != null;
        }
    }

    /**
     * A Selector's rate is scoped to the Selector, not the exporter. One exporter may run several
     * concurrently — RFC 5476 §6.5.2 scopes every Selector Report by {@code selectorId} for exactly
     * that reason — and a flow record names which one produced it.
     */
    public record SelectorKey(ExporterIdentity identity, long selectorId) {
    }

    /**
     * Twice the slowest default refresh among the platforms this targets. IOS-XE defaults
     * {@code option sampler-table timeout} to 600 s, but IOS-XR — the ASR9k this was built against
     * — defaults {@code options sampler-table timeout} to 1800 s. A TTL shorter than the refresh
     * expires mid-cycle, and the exporter's flows then flap between its real rate and "unsampled"
     * with nothing in the data to tell the two apart.
     */
    private static final Duration RETENTION = Duration.ofMinutes(60);

    /**
     * One entry per exporter and observation domain. Bounded because entries arrive from whatever
     * sends option records, including spoofed sources, and lazy expiry alone would let a burst grow
     * the map for a whole TTL window.
     */
    private static final long MAX_EXPORTERS = 8_192;

    private final Cache<ExporterIdentity, AdvertisedRate> table;

    /**
     * Selector Reports, keyed by the Selector they describe. Held separately from the stated table
     * rather than merged into it: an exporter may send both record types, and one map keyed by
     * exporter alone would make the resolved rate depend on which arrived last.
     */
    private final Cache<SelectorKey, AdvertisedRate> selectors;

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;
    private final Meter selectorsConsumed;
    private final Meter selectorsSkipped;
    private final Meter lookupsResolved;
    private final Meter lookupsUnresolved;

    public ExporterSamplingTable(final MetricRegistry metrics) {
        this.table = CacheBuilder.newBuilder()
                .expireAfterWrite(RETENTION)
                .maximumSize(MAX_EXPORTERS)
                .build();
        this.selectors = CacheBuilder.newBuilder()
                .expireAfterWrite(RETENTION)
                .maximumSize(MAX_EXPORTERS)
                .build();
        this.selectorsConsumed = metrics.meter(MetricRegistry.name("parser", "selectorReport", "consumed"));
        this.selectorsSkipped = metrics.meter(MetricRegistry.name("parser", "selectorReport", "skipped"));
        this.recordsConsumed = metrics.meter(MetricRegistry.name("parser", "optionSampling", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("parser", "optionSampling", "skipped"));
        this.lookupsResolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "resolved"));
        this.lookupsUnresolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "unresolved"));
    }

    @Override
    public void accept(final ExporterIdentity identity, final Collection<Value<?>> scopes, final List<Value<?>> values) {
        if (acceptSamplerOptions(identity, values)) {
            return;
        }
        acceptSelectorReport(identity, scopes, values);
    }

    /**
     * An IE 34/35 (or the v9 48/49/50) sampler options record, which states its interval outright.
     *
     * @return whether the record was recognised as one, so a caller knows not to try other shapes
     */
    private boolean acceptSamplerOptions(final ExporterIdentity identity, final List<Value<?>> values) {
        final Double interval = unsigned(values, INTERVAL_FIELDS);
        if (interval == null) {
            return false; // not a sampler option record (interface/VRF/app tables, …)
        }
        if (!isUsableRate(interval)) {
            // An explicit 1 is kept: it means "not sampling", which is an answer, and dropping it
            // would let a receiver-wide fallback meant for a different exporter override this one.
            // 0 is different — an exporter re-advertises 0 when sampling is turned off, so treat it
            // as a withdrawal and drop what was learned rather than serving it until the TTL runs.
            this.table.invalidate(identity);
            this.recordsSkipped.mark();
            return true;
        }
        final Double mode = unsigned(values, MODE_FIELDS);
        this.table.put(identity, new AdvertisedRate(interval, mode != null ? mode.intValue() : null));
        this.recordsConsumed.mark();
        return true;
    }

    /**
     * An RFC 5476 §6.5.2 Selector Report: {@code selectorId} as the scope, {@code selectorAlgorithm}
     * and that algorithm's parameters as fields. This is where the protocol puts selector
     * parameters, and riptide used to look for them on flow records instead (#584).
     *
     * <p>{@code selectorId} is read from the scopes and then from the fields. The RFC puts it in
     * the scope, and that is the only position verified here, but no exporter sending one of these
     * has been available to test against and the fallback costs a single pass.</p>
     */
    private void acceptSelectorReport(final ExporterIdentity identity,
                                      final Collection<Value<?>> scopes,
                                      final List<Value<?>> values) {
        final Double selectorId = Optional.ofNullable(numeric(scopes, "selectorId"))
                .orElseGet(() -> numeric(values, "selectorId"));
        final Double algorithm = numeric(values, "selectorAlgorithm");
        if (selectorId == null || algorithm == null) {
            return; // not a Selector Report
        }
        final Double rate = SelectorReport.rate(algorithm.intValue(), name -> numeric(values, name));
        if (rate == null || !isUsableRate(rate)) {
            // The report named an algorithm that expresses no ratio, or named one and omitted its
            // parameters. Either way nothing was learned, and recording a 1.0 here would claim the
            // Selector does not sample — see SelectorReport for why that claim is not available.
            this.selectorsSkipped.mark();
            return;
        }
        this.selectors.put(new SelectorKey(identity, selectorId.longValue()),
                new AdvertisedRate(rate, null, algorithm.intValue()));
        this.selectorsConsumed.mark();
    }

    /**
     * The rate this exporter advertised, empty when it has not advertised one (yet).
     *
     * <p>A miss is metered, and callers are expected to ask only when they actually need an
     * answer — metering a lookup whose result is discarded would report a permanent miss for every
     * exporter that puts its rate on the record. Immediately after start a miss is expected: the
     * exporter re-sends its options table on its own timer, and nothing can be known before it
     * arrives. A miss rate that does not settle means this exporter never advertises one, and
     * needs {@code flow-sampling-interval-fallback} configured instead.
     */
    public Optional<AdvertisedRate> lookup(final ExporterIdentity identity) {
        return lookup(identity, null);
    }

    /**
     * The rate for the Selector this flow names, falling back to what the exporter advertised for
     * itself.
     *
     * <p>A Selector Report describes one Selector out of however many the exporter runs, so a flow
     * naming {@code selectorId} is asking a narrower question than one that does not. Where both a
     * report for that Selector and an exporter-wide advertisement exist, the report wins on
     * specificity — not because a computed rate outranks a stated one, which it does not.</p>
     *
     * <p>A flow naming no Selector gets the exporter-wide rate and nothing else. Matching an
     * unreferenced report to it would mean guessing which Selector produced the flow, and where an
     * exporter runs several there is no answer to guess at.</p>
     */
    public Optional<AdvertisedRate> lookup(final ExporterIdentity identity, final Long selectorId) {
        final AdvertisedRate rate = resolve(identity, selectorId);
        if (rate == null) {
            this.lookupsUnresolved.mark();
            return Optional.empty();
        }
        this.lookupsResolved.mark();
        return Optional.of(rate);
    }

    private AdvertisedRate resolve(final ExporterIdentity identity, final Long selectorId) {
        if (identity == null) {
            return null;
        }
        if (selectorId != null) {
            final AdvertisedRate selector = this.selectors.getIfPresent(new SelectorKey(identity, selectorId));
            if (selector != null) {
                return selector;
            }
        }
        return this.table.getIfPresent(identity);
    }

    private static boolean isUsableRate(final double interval) {
        return Double.isFinite(interval) && interval >= 1.0;
    }

    /**
     * One numeric field by its IANA name, whatever width or signedness the exporter encoded it in.
     *
     * <p>{@link DoubleVisitor} rather than {@link UnsignedLongVisitor} because the selector
     * parameters are not all unsigned: {@code samplingProbability} (IE 311) is a float64, and an
     * unsigned visitor returns null for it.</p>
     */
    private static Double numeric(final Collection<Value<?>> values, final String name) {
        for (final Value<?> value : values) {
            if (name.equals(value.getName())) {
                final Double numeric = value.accept(new DoubleVisitor());
                if (numeric != null) {
                    return numeric;
                }
            }
        }
        return null;
    }

    private static Double unsigned(final Collection<Value<?>> values, final List<String> names) {
        for (final String name : names) {
            for (final Value<?> value : values) {
                if (name.equals(value.getName())) {
                    final UnsignedLong u = value.accept(new UnsignedLongVisitor());
                    if (u != null) {
                        return u.doubleValue();
                    }
                }
            }
        }
        return null;
    }
}
