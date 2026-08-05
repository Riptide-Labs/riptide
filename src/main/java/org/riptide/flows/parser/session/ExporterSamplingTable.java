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
 * <p>Keyed by {@link ExporterIdentity} alone, which already carries source address plus
 * observation domain. Deliberately not keyed by {@code FLOW_SAMPLER_ID}: one rate per exporter and
 * observation domain is the scope goflow2 and NetGauze both settled on, and a second key on a
 * field many exporters omit from data records buys nothing.
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

    /** What an exporter advertised: the interval, and the mode when it stated one. */
    public record AdvertisedRate(double interval, Integer mode) {
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

    private final Meter recordsConsumed;
    private final Meter recordsSkipped;
    private final Meter lookupsResolved;
    private final Meter lookupsUnresolved;

    public ExporterSamplingTable(final MetricRegistry metrics) {
        this.table = CacheBuilder.newBuilder()
                .expireAfterWrite(RETENTION)
                .maximumSize(MAX_EXPORTERS)
                .build();
        this.recordsConsumed = metrics.meter(MetricRegistry.name("parser", "optionSampling", "consumed"));
        this.recordsSkipped = metrics.meter(MetricRegistry.name("parser", "optionSampling", "skipped"));
        this.lookupsResolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "resolved"));
        this.lookupsUnresolved = metrics.meter(MetricRegistry.name("parser", "optionSampling", "unresolved"));
    }

    @Override
    public void accept(final ExporterIdentity identity, final Collection<Value<?>> scopes, final List<Value<?>> values) {
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
        final AdvertisedRate rate = identity != null ? this.table.getIfPresent(identity) : null;
        if (rate == null) {
            this.lookupsUnresolved.mark();
            return Optional.empty();
        }
        this.lookupsResolved.mark();
        return Optional.of(rate);
    }

    private static boolean isUsableRate(final double interval) {
        return Double.isFinite(interval) && interval >= 1.0;
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
