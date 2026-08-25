/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.beans.factory.annotation.Autowired;
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
     * Longer than any exporter's options refresh interval, which is the only property this window
     * needs (#593).
     *
     * <p>It used to be 60 minutes — twice IOS-XR's {@code options sampler-table timeout} default of
     * 1800 s — and any exporter refreshing more slowly flapped: the entry expired before the next
     * advertisement, the rate reverted to {@code assumed} / 1, and returned on the next refresh,
     * once per cycle indefinitely. Since #585 made {@code samplingInterval} a rollup dimension each
     * flap also splits a rollup group in a table retained for a year, and since #590 the value
     * governs IPFIX exporters whose refresh interval is operator-configured with no default riptide
     * can reason about.</p>
     *
     * <p><b>Why a single generous constant rather than something measured.</b> The costs here are
     * asymmetric. A rate change is <em>pushed</em> — the exporter re-advertises and the new value
     * overwrites — so this window never protects against a stale <em>wrong</em> rate. It governs
     * only what happens when an exporter goes quiet. Erring long serves a rate that was true
     * recently, rarely. Erring short presents a known-wrong value as an answer, every cycle,
     * forever.</p>
     *
     * <p>An earlier attempt derived the window from each exporter's observed refresh cadence, at
     * four times the measured interval. It was wrong three times over, each fix exposing the next
     * hole: a bootstrap shorter than the cadence made the cadence unlearnable; measuring from the
     * latest gap let a mid-cycle repeat collapse the window; and treating "no cadence yet" as a
     * cadence of zero let a repeat in the very first burst do the same. That last shape is ordinary
     * — an exporter whose sampler table holds two entries sends two records per burst, which is
     * exactly why this class does not key by {@code FLOW_SAMPLER_ID}. Every failure reinstated the
     * flap the estimator existed to remove. What the estimator bought over a flat window was
     * dropping a <em>dead</em> exporter after four cycles instead of after a day; that is not worth
     * paying for with the bug itself.</p>
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    /**
     * One entry per exporter and observation domain. Bounded because entries arrive from whatever
     * sends option records, including spoofed sources, and lazy expiry alone would let a burst grow
     * the map for a whole retention window — now a day rather than an hour, so the bound is reached
     * sooner. Reaching it evicts a real exporter's entry as readily as a stray one, which is why
     * that is counted on {@code …evicted} rather than {@code …expired}.
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
    private final Meter recordsExpired;
    private final Meter recordsEvicted;
    private final Meter selectorsConsumed;
    private final Meter selectorsSkipped;
    private final Meter selectorsExpired;
    private final Meter selectorsEvicted;
    private final Meter lookupsResolved;
    private final Meter lookupsUnresolved;

    /**
     * {@code @Autowired} because the test constructor below makes two. Spring auto-selects a sole
     * constructor and otherwise falls back to looking for a no-arg one, so adding the second without
     * this marker breaks every context that wires this bean.
     */
    @Autowired
    public ExporterSamplingTable(final MetricRegistry metrics) {
        this(metrics, Ticker.systemTicker());
    }

    /** Visible for testing: a fake ticker exercises expiry without wall-clock waits. */
    ExporterSamplingTable(final MetricRegistry metrics, final Ticker ticker) {
        this.selectorsConsumed = metrics.meter(MetricRegistry.name("parser", "selectorReport", "consumed"));
        this.selectorsSkipped = metrics.meter(MetricRegistry.name("parser", "selectorReport", "skipped"));
        this.selectorsExpired = metrics.meter(MetricRegistry.name("parser", "selectorReport", "expired"));
        this.selectorsEvicted = metrics.meter(MetricRegistry.name("parser", "selectorReport", "evicted"));
        this.recordsConsumed = metrics.meter(MetricRegistry.name("parser", "optionSampling", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("parser", "optionSampling", "skipped"));
        this.recordsExpired = metrics.meter(MetricRegistry.name("parser", "optionSampling", "expired"));
        this.recordsEvicted = metrics.meter(MetricRegistry.name("parser", "optionSampling", "evicted"));
        this.lookupsResolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "resolved"));
        this.lookupsUnresolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "unresolved"));
        this.table = build(ticker, this.recordsExpired, this.recordsEvicted);
        this.selectors = build(ticker, this.selectorsExpired, this.selectorsEvicted);
    }

    /**
     * One bounded map that reports why it dropped an entry.
     *
     * <p>Expiry and size eviction are counted apart because they want different responses: expiry
     * means an exporter went quiet, size means the table is full and is displacing entries that may
     * still be live. An explicit {@code invalidate} — a withdrawal — carries {@code EXPLICIT} and is
     * counted as neither.</p>
     */
    private static <K, V> Cache<K, V> build(final Ticker ticker, final Meter expired, final Meter evicted) {
        return CacheBuilder.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(RETENTION)
                .maximumSize(MAX_EXPORTERS)
                .<K, V>removalListener(notification -> {
                    if (notification.getCause() == RemovalCause.EXPIRED) {
                        expired.mark();
                    } else if (notification.getCause() == RemovalCause.SIZE) {
                        evicted.mark();
                    }
                })
                .build();
    }

    /** Visible for testing: runs pending maintenance so an expiry that is due has happened. */
    void cleanUp() {
        this.table.cleanUp();
        this.selectors.cleanUp();
    }

    /**
     * Routed on the scope, because that is what tells the two record shapes apart. RFC 5476 §6.5.2
     * requires a Selector Report to be scoped by {@code selectorId}; a sampler options record is
     * scoped by the system or the observation domain.
     *
     * <p>Not routed on which fields are present. An earlier draft tried the sampler-options reader
     * first and fell through to the Selector Report only when no interval field was found, which
     * files a {@code selectorId}-scoped record that happens to state IE 34 under the exporter
     * instead of under its Selector. Two Selectors announcing rates that way would then overwrite
     * one another, last write winning, and every flow from both would be scaled by whichever
     * arrived most recently — the arrival-order dependence the two maps exist to prevent.</p>
     */
    @Override
    public void accept(final ExporterIdentity identity, final Collection<Value<?>> scopes, final List<Value<?>> values) {
        final UnsignedLong selectorId = exact(scopes, "selectorId");
        if (selectorId != null) {
            acceptSelectorReport(identity, selectorId, values);
            return;
        }
        acceptSamplerOptions(identity, values);
    }

    /** An IE 34/35 (or the v9 48/49/50) sampler options record, which states its interval outright. */
    private void acceptSamplerOptions(final ExporterIdentity identity, final List<Value<?>> values) {
        final Double interval = unsigned(values, INTERVAL_FIELDS);
        if (interval == null) {
            return; // not a sampler option record (interface/VRF/app tables, …)
        }
        if (!isUsableRate(interval)) {
            // An explicit 1 is kept: it means "not sampling", which is an answer, and dropping it
            // would let a receiver-wide fallback meant for a different exporter override this one.
            // 0 is different — an exporter re-advertises 0 when sampling is turned off, so treat it
            // as a withdrawal and drop what was learned rather than serving it until the TTL runs.
            this.table.invalidate(identity);
            this.recordsSkipped.mark();
            return;
        }
        final Double mode = unsigned(values, MODE_FIELDS);
        this.table.put(identity, new AdvertisedRate(interval, mode != null ? mode.intValue() : null));
        this.recordsConsumed.mark();
    }

    /**
     * An RFC 5476 §6.5.2 Selector Report: {@code selectorId} as the scope, {@code selectorAlgorithm}
     * and that algorithm's parameters as fields. This is where the protocol puts selector
     * parameters, and riptide used to look for them on flow records instead (#584).
     */
    private void acceptSelectorReport(final ExporterIdentity identity,
                                      final UnsignedLong selectorId,
                                      final List<Value<?>> values) {
        final SelectorKey key = new SelectorKey(identity, selectorId.longValue());
        final Double algorithm = numeric(values, "selectorAlgorithm");
        final Double computed = algorithm != null
                ? SelectorReport.rate(algorithm.intValue(), name -> numeric(values, name))
                : null;
        if (computed != null && isUsableRate(computed)) {
            this.selectors.put(key, new AdvertisedRate(computed, null, algorithm.intValue()));
            this.selectorsConsumed.mark();
            return;
        }
        final Double stated = unsigned(values, INTERVAL_FIELDS);
        if (stated != null && isUsableRate(stated)) {
            // A Selector-scoped record may state its rate outright rather than as parameters. Kept
            // under the Selector's key, but without the algorithm, so it reports as `options`:
            // nothing was computed, and provenance says which.
            final AdvertisedRate rate = new AdvertisedRate(stated, unsignedOrNull(values, MODE_FIELDS));
            this.selectors.put(key, rate);
            // …and mirrored exporter-wide, because a stated interval is the same number whichever
            // Selector announced it. Before #594 this record was filed under the exporter alone and
            // served every flow; keying it only by Selector silently stranded any exporter that
            // scopes its sampler options by selectorId without echoing IE 302 onto its data records.
            // Only a stated rate is mirrored — a rate computed from one Selector's parameters says
            // nothing about the others, which is the guess `lookup` declines to make.
            this.table.put(identity, rate);
            this.selectorsConsumed.mark();
            return;
        }
        if (algorithm == null) {
            // Scoped by selectorId but neither a Selector Report nor a rate: some other per-Selector
            // options record. Ignored, exactly as `acceptSamplerOptions` ignores an options record
            // that is not about sampling. Absence is not a withdrawal — only a Selector Report that
            // has stopped expressing a ratio is that, and it says so by naming its algorithm.
            return;
        }
        // The report named an algorithm expressing no ratio, or omitted its parameters. Recording a
        // 1.0 would claim the Selector does not sample — see SelectorReport for why that claim is
        // not available — and keeping the previous rate would serve one the exporter has just
        // stopped advertising for the whole retention window.
        this.selectors.invalidate(key);
        this.selectorsSkipped.mark();
    }

    /** {@link #unsigned} as a boxed {@code Integer}, since that is what {@link AdvertisedRate} holds. */
    private static Integer unsignedOrNull(final Collection<Value<?>> values, final List<String> names) {
        final Double value = unsigned(values, names);
        return value != null ? value.intValue() : null;
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
     * One unsigned field by its IANA name, at full width.
     *
     * <p>Used for {@code selectorId}, which is an unsigned64 and is a <em>key</em>. Reading it
     * through {@link #numeric} would round it above 2^53 and saturate above 2^63, while
     * {@code IpFixFlowBuilder} reads the same element off a flow record as an exact
     * {@link UnsignedLong}. The two {@link SelectorKey}s would then disagree for a wide selector id
     * and the report would be silently unreachable, with nothing to show for it but a rising
     * unresolved-lookup meter.</p>
     */
    private static UnsignedLong exact(final Collection<Value<?>> values, final String name) {
        for (final Value<?> value : values) {
            if (name.equals(value.getName())) {
                final UnsignedLong exact = value.accept(new UnsignedLongVisitor());
                if (exact != null) {
                    return exact;
                }
            }
        }
        return null;
    }

    /**
     * One numeric field by its IANA name, whatever width or signedness the exporter encoded it in.
     *
     * <p>{@link DoubleVisitor} rather than {@link UnsignedLongVisitor} because the selector
     * parameters are not all unsigned: {@code samplingProbability} (IE 311) is a float64, and an
     * unsigned visitor returns null for it. Safe for the parameters, which are quantities feeding
     * a division; not safe for {@code selectorId}, which is a key — see {@link #exact}.</p>
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
