/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * A Juniper SRX advertising its sampling rate out of band, driven through the real IPFIX parse
 * path (Packet → session.addOptions → option tap) into the sampling table and back out as a flow.
 *
 * <p>Every byte here is captured, not constructed. An SRX 345 running inline-jflow at
 * {@code input rate 100} exported these two messages to a live collector; the options template,
 * its data record, the data template and the flow records are all as the hardware sent them.
 * That matters because this rung had never been exercised against a real IPFIX exporter: riptide
 * consumed these records into the table and no IPFIX code path read them back, so every Juniper
 * deployment reported {@code assumed} / {@code 1} while sampling 1:100 (#589).</p>
 *
 * <p>What the SRX sends, decoded from the capture:</p>
 * <pre>
 * options template 257
 *   scope  IE 149  observationDomainId
 *   field  IE 35   samplingAlgorithm  = 2   (random n-out-of-N)
 *   field  IE 34   samplingInterval   = 100
 * </pre>
 */
public class JuniperSamplerOptionsBlackboxTest {

    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    /** The observation domain the captured SRX fixtures were exported from (the inet family). */
    private static final long OBSERVATION_DOMAIN = 268435456L;

    /** What the SRX advertises: {@code input rate 100}. */
    private static final double ADVERTISED_RATE = 100.0;

    private final ExporterSamplingTable table = new ExporterSamplingTable(new MetricRegistry());

    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.table);

    private void parseIpfix(final ByteBuf buf) throws Exception {
        do {
            new Packet(this.session, new Header(slice(buf, Header.SIZE)), buf);
        } while (buf.isReadable());
    }

    private void feedTheCapturedOptionsRecord() throws Exception {
        parseIpfix(Unpooled.wrappedBuffer(
                Files.readAllBytes(FOLDER.resolve("ipfix_test_juniper_srx_opttpl257.dat"))));
    }

    private IpFixFlowBuilder builder() {
        final var builder = new IpFixFlowBuilder(
                new ValueConversionService(IpfixRawFlow.class, VISITORS));
        builder.setSamplingTable(this.table);
        return builder;
    }

    /** The option tap consumes what the SRX actually sends — IE 34, not the v9 field 50. */
    @Test
    public void theAdvertisedRateReachesTheSamplingTable() throws Exception {
        feedTheCapturedOptionsRecord();

        final var identity =
                new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);

        assertThat(this.table.lookup(identity).map(ExporterSamplingTable.AdvertisedRate::interval))
                .as("the SRX states samplingInterval=100 in an options record; the table must hold it")
                .contains(ADVERTISED_RATE);
    }

    /**
     * The rung this change adds: a flow whose record states no rate resolves to the advertised one.
     *
     * <p>Before this, the table held 100 and the flow reported 1.0/{@code assumed} — the rate was
     * learned and discarded, which is the whole of #589.</p>
     */
    @Test
    public void aFlowWithNoRateOfItsOwnResolvesToTheAdvertisedRate() throws Exception {
        feedTheCapturedOptionsRecord();

        final var identity =
                new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);
        final var raw = new IpfixRawFlow();

        final Flow flow = builder().buildFlow(Instant.EPOCH, raw, this.table.lookup(identity).orElse(null));

        assertThat(flow.getSamplingInterval()).isEqualTo(ADVERTISED_RATE);
        assertThat(flow.getSamplingProvenance())
                .as("a rate the exporter advertised is `options`, never `assumed`")
                .isEqualTo(Flow.SamplingProvenance.Options);
    }

    /** And the mode travels with it, so the algorithm is not left Unassigned beside a real rate. */
    @Test
    public void theAdvertisedModeSuppliesTheSamplingAlgorithm() throws Exception {
        feedTheCapturedOptionsRecord();

        final var identity =
                new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);
        final Flow flow = builder().buildFlow(Instant.EPOCH, new IpfixRawFlow(),
                this.table.lookup(identity).orElse(null));

        assertThat(flow.getSamplingAlgorithm())
                .as("the SRX advertises samplingAlgorithm=2 alongside the interval")
                .isNotEqualTo(Flow.SamplingAlgorithm.Unassigned);
    }

    /**
     * A rate on the record still outranks the advertised one.
     *
     * <p>The ordering is the point: the advertised rate is a property of the exporter, and a rate on
     * the record describes the flow in front of you.</p>
     */
    @Test
    public void aRateOnTheRecordOutranksTheAdvertisedOne() throws Exception {
        feedTheCapturedOptionsRecord();

        final var identity =
                new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);
        final var raw = new IpfixRawFlow();
        raw.samplingInterval = 512.0;

        final Flow flow = builder().buildFlow(Instant.EPOCH, raw, this.table.lookup(identity).orElse(null));

        assertThat(flow.getSamplingInterval()).isEqualTo(512.0);
        assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Record);
    }

    /**
     * The seam between learning and resolving, which the v9 test guards for the same reason: the
     * identity {@code IpfixUdpParser} builds for a data packet must be the identity the option tap
     * stored the rate under. They are constructed on opposite sides of the codebase, and a mismatch
     * would leave every lookup missing while both halves passed their own tests.
     */
    @Test
    public void theIdentityTheParserBuildsFindsTheRateTheTapStored() throws Exception {
        feedTheCapturedOptionsRecord();

        // exactly what IpfixUdpParser.parse() constructs: session address plus observation domain
        final var asTheParserBuildsIt =
                new ExporterIdentity.NetflowIpfix(this.session.getRemoteAddress(), OBSERVATION_DOMAIN);

        assertThat(this.table.lookup(asTheParserBuildsIt).map(ExporterSamplingTable.AdvertisedRate::interval))
                .contains(ADVERTISED_RATE);
    }

    /** Real flow records from the same exporter, carrying no rate of their own. */
    @Test
    public void capturedFlowRecordsResolveToTheAdvertisedRate() throws Exception {
        feedTheCapturedOptionsRecord();
        final var identity =
                new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);
        final var advertised = this.table.lookup(identity).orElse(null);

        final ByteBuf data = Unpooled.wrappedBuffer(
                Files.readAllBytes(FOLDER.resolve("ipfix_test_juniper_srx_data270.dat")));
        final Packet packet = new Packet(this.session, new Header(slice(data, Header.SIZE)), data);

        final List<Flow> flows = builder().buildFlows(Instant.EPOCH, packet,
                        new ExporterIdentity.NetflowIpfix(this.session.getRemoteAddress(), OBSERVATION_DOMAIN))
                .toList();

        assertThat(flows).as("the captured data set carries flow records").isNotEmpty();
        assertThat(flows).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval()).isEqualTo(ADVERTISED_RATE);
            assertThat(flow.getSamplingProvenance()).isEqualTo(Flow.SamplingProvenance.Options);
        });
        assertThat(advertised).isNotNull();
    }
}
