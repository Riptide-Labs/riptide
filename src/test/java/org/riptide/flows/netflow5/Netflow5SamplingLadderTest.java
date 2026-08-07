/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow5;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.netflow5.Netflow5FlowBuilder;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow5.proto.Record;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * The NetFlow v5 sampling ladder: header, then configured fallback, then unsampled.
 *
 * <p>The v5 header packs a 2-bit algorithm and a 14-bit interval into one word, and the two
 * non-zero cases are governed differently. Algorithm 1 or 2 is unambiguous and always read.
 * Algorithm 0 with a non-zero interval is the contested case — self-contradictory on its face, but
 * routinely emitted by sampling exporters because the mode bits are not mandatory — and it is the
 * only thing {@code trust-header-sampling-interval} governs.
 */
class Netflow5SamplingLadderTest {

    /**
     * Every v5 capture in the corpus, pinned to its measured header word.
     *
     * <p>These values are the evidence the decision rests on, so they are asserted rather than
     * described: two exporters advertise a round decimal rate with the mode bits clear, and the two
     * that are not sampling advertise a clean zero. A change that silently re-reads the field
     * differently has to break this test to do it.
     */
    @Test
    void everyCaptureInTheCorpusReportsItsMeasuredRate() throws Exception {
        assertThat(intervalFromCapture("/flows/netflow5.dat"))
                .as("0x0000 — not sampling")
                .isEqualTo(1.0);
        assertThat(intervalFromCapture("/flows/netflow5_test_microtik.dat"))
                .as("0x0000 — not sampling")
                .isEqualTo(1.0);
        assertThat(intervalFromCapture("/flows/netflow5_test_juniper_mx80.dat"))
                .as("0x03e8 — algorithm 0, interval 1000")
                .isEqualTo(1000.0);
        assertThat(intervalFromCapture("/flows/jflow-packet.dat"))
                .as("0x0014 — algorithm 0, interval 20")
                .isEqualTo(20.0);
    }

    /**
     * The pair #445 worried about, asserted as intended rather than tolerated.
     *
     * <p>{@code Unassigned} beside a non-unity interval says the rate is known and the mode is not.
     * NetFlow v9 already emits exactly this whenever a record carries field 34 with no mode field,
     * and every protocol emits it when the configured fallback supplies the rate. Do not "fix" this
     * by suppressing the interval — see {@code Netflow9FlowBuilder#getSamplingAlgorithm}.
     */
    @Test
    void anUnassignedAlgorithmBesideARealRateIsIntended() throws Exception {
        final var flow = onlyFlow(packet(0, 1000), builder(null, true));

        assertThat(flow.getSamplingInterval()).isEqualTo(1000.0);
        assertThat(flow.getSamplingAlgorithm()).isEqualTo(Flow.SamplingAlgorithm.Unassigned);
    }

    @Test
    void aZeroIntervalMeansUnsampled() throws Exception {
        assertThat(onlyFlow(packet(0, 0), builder(null, true)).getSamplingInterval())
                .isEqualTo(1.0);
    }

    @Test
    void theHeaderBeatsTheConfiguredFallback() throws Exception {
        assertThat(onlyFlow(packet(0, 20), builder(500L, true)).getSamplingInterval())
                .isEqualTo(20.0);
    }

    @Test
    void configurationFillsTheGapASilentExporterLeaves() throws Exception {
        assertThat(onlyFlow(packet(0, 0), builder(500L, true)).getSamplingInterval())
                .isEqualTo(500.0);
    }

    @Test
    void anExplicitHeaderIntervalOfOneIsNotOverriddenByTheFallback() throws Exception {
        assertThat(onlyFlow(packet(0, 1), builder(500L, true)).getSamplingInterval())
                .isEqualTo(1.0);
    }

    /**
     * A mode-signalling exporter is unambiguous, so its rate wins over configuration. Without this
     * the fallback would override a rate the exporter explicitly stated — the inverse of the
     * precedence riptide documents for v9 and IPFIX.
     */
    @Test
    void aModeSignallingExporterBeatsTheConfiguredFallback() throws Exception {
        final var flow = onlyFlow(packet(1, 100), builder(1000L, true));

        assertThat(flow.getSamplingInterval()).isEqualTo(100.0);
        assertThat(flow.getSamplingAlgorithm())
                .isEqualTo(Flow.SamplingAlgorithm.SystematicCountBasedSampling);
    }

    @Test
    void theContestedCaseCanBePinnedOff() throws Exception {
        assertThat(onlyFlow(packet(0, 1000), builder(null, false)).getSamplingInterval())
                .as("algorithm 0 falls through when the operator has pinned it off")
                .isEqualTo(1.0);
        assertThat(onlyFlow(packet(0, 1000), builder(500L, false)).getSamplingInterval())
                .as("and lands on the configured fallback when there is one")
                .isEqualTo(500.0);
    }

    /**
     * The opt-out is scoped to the contested case. An operator pinning behaviour for a mode-0 fleet
     * must not thereby discard correct rates from an exporter that signalled its mode properly.
     */
    @Test
    void pinningTheContestedCaseOffDoesNotSuppressAStatedMode() throws Exception {
        final var flow = onlyFlow(packet(2, 512), builder(null, false));

        assertThat(flow.getSamplingInterval()).isEqualTo(512.0);
        assertThat(flow.getSamplingAlgorithm())
                .isEqualTo(Flow.SamplingAlgorithm.RandomNOutOfNSampling);
    }

    /**
     * Metered per packet, not per flow: the rate lives in the header, so every record in a packet
     * resolves identically and counting each one would report the same fact once per flow.
     */
    @Test
    void whichRungAnsweredIsMeteredOncePerPacket() throws Exception {
        final var metrics = new MetricRegistry();
        final var builder = new Netflow5FlowBuilder(metrics);
        builder.setFlowSamplingIntervalFallback(500L);

        assertThat(builder.buildFlows(Instant.EPOCH, packet(0, 1000, 3)).toList()).hasSize(3);
        assertThat(builder.buildFlows(Instant.EPOCH, packet(0, 0, 2)).toList()).hasSize(2);
        assertThat(builder.buildFlows(Instant.EPOCH, packet(0, 0, 1)).toList()).hasSize(1);

        assertThat(meter(metrics, "header"))
                .as("one packet resolved from the header, not three flows")
                .isEqualTo(1);
        assertThat(meter(metrics, "fallback")).as("two packets fell through to config").isEqualTo(2);
        assertThat(meter(metrics, "unsampled")).as("no packet reached the default").isZero();
    }

    @Test
    void aPacketWithNoRateAnywhereIsMeteredAsUnsampled() throws Exception {
        final var metrics = new MetricRegistry();
        assertThat(new Netflow5FlowBuilder(metrics).buildFlows(Instant.EPOCH, packet(0, 0)).toList())
                .hasSize(1);

        assertThat(meter(metrics, "unsampled")).isEqualTo(1);
        assertThat(meter(metrics, "header")).isZero();
    }

    private static long meter(final MetricRegistry metrics, final String rung) {
        return metrics.meter(MetricRegistry.name("parser", "netflow5Sampling", rung)).getCount();
    }

    private static Netflow5FlowBuilder builder(final Long fallback, final boolean trustHeader) {
        final var builder = new Netflow5FlowBuilder(new MetricRegistry());
        builder.setFlowSamplingIntervalFallback(fallback);
        builder.setTrustHeaderSamplingInterval(trustHeader);
        return builder;
    }

    private static Flow onlyFlow(final Packet packet, final Netflow5FlowBuilder builder) {
        final List<Flow> flows = builder.buildFlows(Instant.EPOCH, packet).toList();
        assertThat(flows).hasSize(1);
        return flows.getFirst();
    }

    private static Packet packet(final int algorithm, final int interval) throws InvalidPacketException {
        return packet(algorithm, interval, 1);
    }

    /** A minimal v5 packet: a header carrying the sampling word, and {@code records} zeroed records. */
    private static Packet packet(final int algorithm, final int interval, final int records)
            throws InvalidPacketException {
        final ByteBuf buffer = Unpooled.buffer(Header.SIZE + records * Record.SIZE);
        buffer.writeShort(Header.VERSION);
        buffer.writeShort(records);
        buffer.writeInt(0);          // sysUptime
        buffer.writeInt(0);          // unixSecs
        buffer.writeInt(0);          // unixNSecs
        buffer.writeInt(0);          // flowSequence
        buffer.writeByte(0);         // engineType
        buffer.writeByte(0);         // engineId
        buffer.writeShort((algorithm << 14) | interval);
        buffer.writeZero(records * Record.SIZE);

        final Header header = new Header(slice(buffer, Header.SIZE));
        return new Packet(header, buffer);
    }

    private static double intervalFromCapture(final String resource)
            throws InvalidPacketException, URISyntaxException, IOException {
        final var url = Netflow5SamplingLadderTest.class.getResource(resource);
        final ByteBuf buffer = Unpooled.wrappedBuffer(Files.readAllBytes(Paths.get(url.toURI())));
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(header, buffer);

        return new Netflow5FlowBuilder(new MetricRegistry())
                .buildFlows(Instant.EPOCH, packet)
                .findFirst()
                .orElseThrow()
                .getSamplingInterval();
    }
}
