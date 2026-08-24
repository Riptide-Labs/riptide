/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Ticker;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long a learned rate survives its exporter's silence (#593).
 *
 * <p>The window was 60 minutes, twice IOS-XR's {@code options sampler-table timeout} default. An
 * exporter refreshing more slowly flapped: the entry expired before the next advertisement, the rate
 * reverted to {@code assumed} / 1, and came back on the next refresh — once per cycle, indefinitely.
 * Since #585 made {@code samplingInterval} a rollup dimension, each flap also splits a rollup group
 * in a table retained for a year.</p>
 *
 * <p>What these tests pin is deliberately modest, and that is the point. An earlier version of this
 * change derived the window from each exporter's observed refresh cadence; it was wrong three times,
 * each fix exposing the next hole, and every failure reinstated the flap it existed to remove. The
 * only property that matters is that the window outlasts any real refresh interval, and one generous
 * constant delivers it with nothing left to get wrong.</p>
 *
 * <p>Driven by a fake {@link Ticker} rather than by sleeping.</p>
 */
class ExporterSamplingTableExpiryTest {

    /** Guava's clock, under the test's control. */
    private static final class FakeTicker extends Ticker {
        private long nanos;

        @Override
        public long read() {
            return this.nanos;
        }

        void advance(final Duration by) {
            this.nanos += by.toNanos();
        }
    }

    private final FakeTicker ticker = new FakeTicker();
    private final MetricRegistry metrics = new MetricRegistry();
    private final ExporterSamplingTable table = new ExporterSamplingTable(this.metrics, this.ticker);

    private static ExporterIdentity exporter(final String address) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0);
    }

    /** A v9 sampler options record advertising a rate. */
    private void advertise(final ExporterIdentity identity, final int interval) {
        this.table.accept(identity, List.of(), List.<Value<?>>of(
                new UnsignedValue("FLOW_SAMPLER_RANDOM_INTERVAL", interval)));
    }

    /** A Selector Report stating systematic count-based selection of 1 packet in every 100. */
    private void advertiseSelector(final ExporterIdentity identity, final int selectorId) {
        this.table.accept(identity,
                List.<Value<?>>of(new UnsignedValue("selectorId", selectorId)),
                List.<Value<?>>of(
                        new UnsignedValue("selectorAlgorithm", 1),
                        new UnsignedValue("samplingPacketInterval", 1),
                        new UnsignedValue("samplingPacketSpace", 99)));
    }

    private long meter(final String table, final String name) {
        return this.metrics.meter(MetricRegistry.name("parser", table, name)).getCount();
    }

    /**
     * The bug: an exporter refreshing more slowly than the old window kept losing its rate.
     *
     * <p>Several cycles rather than one, because a flap is a per-cycle event — a window that
     * survives the first gap and not the rest would still flap forever.</p>
     */
    @Test
    void aSlowRefreshingExporterNeverLosesItsRateBetweenRefreshes() throws Exception {
        final var srx = exporter("192.0.2.1");
        final Duration cadence = Duration.ofMinutes(90);   // beyond the old 60-minute window

        for (int cycle = 0; cycle < 8; cycle++) {
            advertise(srx, 1000);
            this.ticker.advance(cadence);

            assertThat(this.table.lookup(srx).map(ExporterSamplingTable.AdvertisedRate::interval))
                    .describedAs("cycle %d", cycle)
                    .contains(1000.0);
        }
    }

    /**
     * Including when it sends more than one record per burst.
     *
     * <p>An exporter whose sampler table holds two entries emits two records each refresh, moments
     * apart. This is the shape that broke the cadence-measuring version: the gap between the two
     * records was read as the refresh interval. It is ordinary rather than exotic, which is why this
     * class does not key by {@code FLOW_SAMPLER_ID} in the first place.</p>
     */
    @Test
    void anExporterSendingSeveralRecordsPerBurstStillKeepsItsRate() throws Exception {
        final var exporter = exporter("192.0.2.2");
        final Duration cadence = Duration.ofMinutes(90);

        for (int cycle = 0; cycle < 8; cycle++) {
            advertise(exporter, 1000);
            this.ticker.advance(Duration.ofSeconds(2));
            advertise(exporter, 1000);
            this.ticker.advance(cadence.minusSeconds(2));

            assertThat(this.table.lookup(exporter))
                    .describedAs("cycle %d", cycle)
                    .isPresent();
        }
    }

    /** A lost advertisement costs a cycle, not the rate. */
    @Test
    void aLostAdvertisementDoesNotExpireTheRate() throws Exception {
        final var exporter = exporter("192.0.2.3");

        advertise(exporter, 1000);
        this.ticker.advance(Duration.ofHours(3));   // three missed refreshes at hourly cadence

        assertThat(this.table.lookup(exporter)).isPresent();
    }

    /** The window still ends. A decommissioned exporter's rate is not served forever. */
    @Test
    void aSilentExporterIsEventuallyDroppedAndCounted() throws Exception {
        final var gone = exporter("192.0.2.4");

        advertise(gone, 1000);
        this.ticker.advance(Duration.ofHours(24).plusMinutes(1));
        this.table.cleanUp();

        assertThat(this.table.lookup(gone)).isEmpty();
        assertThat(meter("optionSampling", "expired"))
                .as("an exporter losing its learned rate must be visible in the exposition")
                .isEqualTo(1L);
    }

    /** Selector Reports get the same window, and their own counter. */
    @Test
    void selectorReportsExpireOnTheSameWindowAndCountSeparately() throws Exception {
        final var exporter = exporter("192.0.2.5");

        advertiseSelector(exporter, 7);
        this.ticker.advance(Duration.ofHours(12));

        assertThat(this.table.lookup(exporter, 7L).map(ExporterSamplingTable.AdvertisedRate::interval))
                .as("a Selector's rate outlasts a refresh interval too")
                .contains(100.0);

        this.ticker.advance(Duration.ofHours(12).plusMinutes(1));
        this.table.cleanUp();

        assertThat(this.table.lookup(exporter, 7L)).isEmpty();
        assertThat(meter("selectorReport", "expired")).isEqualTo(1L);
        assertThat(meter("optionSampling", "expired"))
                .as("the two maps must not share a counter; silence on one says nothing about the other")
                .isZero();
    }

    /**
     * A withdrawal drops the entry immediately and is counted as neither expiry nor eviction.
     *
     * <p>The two mean different things to an operator: silence is a possible fault, an explicit 0 is
     * the exporter saying it has turned sampling off.</p>
     */
    @Test
    void aWithdrawnRateIsNotCountedAsAnExpiry() throws Exception {
        final var exporter = exporter("192.0.2.6");

        advertise(exporter, 1000);
        advertise(exporter, 0);
        this.table.cleanUp();

        assertThat(this.table.lookup(exporter)).isEmpty();
        assertThat(meter("optionSampling", "expired")).isZero();
        assertThat(meter("optionSampling", "evicted")).isZero();
    }

    /** And no exporter comes out of this change with a shorter window than it had. */
    @Test
    void noExporterGetsLessRetentionThanTheOldFixedWindow() throws Exception {
        final var exporter = exporter("192.0.2.7");

        advertise(exporter, 100);
        this.ticker.advance(Duration.ofMinutes(60));

        assertThat(this.table.lookup(exporter))
                .as("the old window was 60 minutes; nothing that worked before may start flapping")
                .isPresent();
    }
}
