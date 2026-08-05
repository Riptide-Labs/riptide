/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.pipeline.ExporterIdentity;

import java.util.List;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Drives a sampler options record through the real v9 parse path (Packet → session.addOptions →
 * option tap) into the sampling table, the seam the unit tests take on trust.
 *
 * <p>The options <em>template</em> is a genuine ASR9k capture; the options <em>data</em> record is
 * built against it, because the repository has no captured data record for template 257. Field
 * ids, widths and order therefore come from real hardware and only the values are synthetic.
 */
public class SamplerOptionsBlackboxTest {

    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    /** The observation domain the captured ASR9k fixtures were exported from. */
    private static final long SOURCE_ID = 2177;

    private final ExporterSamplingTable table = new ExporterSamplingTable(new MetricRegistry());

    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.table);

    private void parseV9(final ByteBuf buf) throws Exception {
        do {
            new Packet(this.session, new Header(slice(buf, Header.SIZE)), buf);
        } while (buf.isReadable());
    }

    /**
     * One options data record for template 257, whose confirmed layout is
     * scope System(4), then FLOW_SAMPLER_ID(2), FLOW_SAMPLER_RANDOM_INTERVAL(4),
     * FLOW_SAMPLER_MODE(1), SAMPLER_NAME(32).
     */
    private static ByteBuf samplerOptionsData(final long randomInterval) {
        final ByteBuf b = Unpooled.buffer();
        b.writeShort(9).writeShort(1)               // version, count
                .writeInt(1000)                     // sysUpTime
                .writeInt(1_700_000_000)            // unixSecs
                .writeInt(2)                        // sequence
                .writeInt((int) SOURCE_ID);         // sourceId

        final int recordLength = 4 + 2 + 4 + 1 + 32;
        b.writeShort(257).writeShort(4 + recordLength + 1); // set id, length incl. header + pad
        b.writeInt(0);                              // scope: System
        b.writeShort(1);                            // FLOW_SAMPLER_ID
        b.writeInt((int) randomInterval);           // FLOW_SAMPLER_RANDOM_INTERVAL
        b.writeByte(2);                             // FLOW_SAMPLER_MODE (random)
        b.writeBytes("SAMPLER-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.writeZero(32 - "SAMPLER-1".length());     // SAMPLER_NAME, NUL-padded
        b.writeZero(1);                             // set padding to a 4-byte boundary
        return b;
    }

    @Test
    public void samplerOptionsRecordReachesTheSamplingTable() throws Exception {
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl257.dat"))));
        parseV9(samplerOptionsData(1000));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID);
        assertThat(this.table.lookup(identity).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(1000.0);
    }

    /**
     * The seam between learning and resolving: the identity {@code Netflow9UdpParser} builds for a
     * data packet must be the identity the option tap stored the rate under. These are constructed
     * on opposite sides of the codebase, and a mismatch in either component would leave every
     * lookup missing while both halves still passed their own tests.
     */
    @Test
    public void theIdentityTheParserBuildsFindsTheRateTheTapStored() throws Exception {
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl257.dat"))));
        parseV9(samplerOptionsData(1000));

        // exactly what Netflow9UdpParser.parse() constructs: session address plus header sourceId
        final var asTheParserBuildsIt =
                new ExporterIdentity.NetflowIpfix(this.session.getRemoteAddress(), SOURCE_ID);

        final var builder = new org.riptide.flows.parser.netflow9.Netflow9FlowBuilder(
                new org.riptide.flows.parser.ie.values.ValueConversionService(
                        org.riptide.flows.parser.netflow9.Netflow9RawFlow.class, VISITORS));
        builder.setSamplingTable(this.table);

        final var raw = new org.riptide.flows.parser.netflow9.Netflow9RawFlow();
        raw.unixSecs = java.time.Instant.EPOCH;
        raw.sysUpTime = java.time.Duration.ZERO;

        assertThat(builder.buildFlow(java.time.Instant.EPOCH, raw,
                this.table.lookup(asTheParserBuildsIt).orElse(null)).getSamplingInterval())
                .isEqualTo(1000.0);
    }

    /** Nothing is known before the exporter sends its table, which is the documented startup gap. */
    @Test
    public void nothingIsKnownBeforeTheOptionsRecordArrives() throws Exception {
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl257.dat"))));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID);
        assertThat(this.table.lookup(identity)).isEmpty();
    }

    /** A refreshed table supersedes the rate, so a re-provisioned sampler is picked up. */
    @Test
    public void aRefreshedTableSupersedesTheRate() throws Exception {
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl257.dat"))));
        parseV9(samplerOptionsData(1000));
        parseV9(samplerOptionsData(4096));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID);
        assertThat(this.table.lookup(identity).map(ExporterSamplingTable.AdvertisedRate::interval)).contains(4096.0);
    }

    /**
     * The interface options table shares this tap and must not be read as a sampler record — the
     * captured ASR9k interface table carries no sampling field at all.
     */
    @Test
    public void theInterfaceOptionsTableIsNotMistakenForASamplerTable() throws Exception {
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl256.dat"))));
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_data256.dat"))));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID);
        assertThat(this.table.lookup(identity)).isEmpty();
    }
}
