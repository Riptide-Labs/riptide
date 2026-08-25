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
import org.riptide.flows.parser.ie.values.ValueConversionService;
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
 * softflowd advertising its packet sampling configuration, driven through the real IPFIX parse path.
 *
 * <p><b>Every byte here is captured.</b> softflowd 1.1.1 running {@code -v 10 -s 100} exported this
 * message to a collector on loopback; the four data templates, the options template, its data record
 * and the flow record are all as the software sent them. That matters because riptide dropped this
 * record entirely (#598): softflowd states its rate in IE 304/305/306 — precisely the elements #594
 * taught riptide to read — but scopes the record by {@code meteringProcessId} rather than
 * {@code selectorId}, and only the latter reached the code that reads them. Every softflowd exporter
 * sampling 1:100 was recorded as unsampled.</p>
 *
 * <p>What softflowd sends, decoded from the capture:</p>
 * <pre>
 * options template 256
 *   scope  IE 143  meteringProcessId
 *   field  IE 160  systemInitTimeMilliseconds
 *   field  IE 305  samplingPacketInterval  = 1
 *   field  IE 306  samplingPacketSpace     = 99
 *   field  IE 304  selectorAlgorithm       = 1   (systematic count-based)
 *   field  IE 82   interfaceName
 *   field  IE 130 / 131 / 403 / 404        exporter and original-exporter addresses
 * </pre>
 *
 * <p>{@code (1 + 99) / 1} is 100, which is what {@code -s 100} asked for.</p>
 *
 * <p>Note the record also carries {@code interfaceName}, so {@code ExporterInterfaceTable}
 * half-matches it and marks {@code skipped} — see #599. That is not this class's concern, but it is
 * why the only meter moving for this record used to belong to interface enrichment.</p>
 */
public class SoftflowdSamplingOptionsBlackboxTest {

    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    /** The observation domain softflowd exports under by default. */
    private static final long OBSERVATION_DOMAIN = 0L;

    /** What {@code -s 100} asks for, and what {@code (interval + space) / interval} yields. */
    private static final double EXPECTED_RATE = 100.0;

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private final ExporterSamplingTable table = new ExporterSamplingTable(new MetricRegistry());

    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.table);

    private final ExporterIdentity identity =
            new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OBSERVATION_DOMAIN);

    private Packet capturedExport() throws Exception {
        final ByteBuf buf = Unpooled.wrappedBuffer(
                Files.readAllBytes(FOLDER.resolve("ipfix_test_softflowd_sampling_opt.dat")));
        final Header header = new Header(slice(buf, Header.SIZE));
        return new Packet(this.session, header, slice(buf, header.payloadLength()));
    }

    private IpFixFlowBuilder builder() {
        final var builder = new IpFixFlowBuilder(new ValueConversionService(IpfixRawFlow.class, VISITORS));
        builder.setSamplingTable(this.table);
        return builder;
    }

    /** The rate reaches the table despite the scope being one riptide had never read. */
    @Test
    public void theAdvertisedRateReachesTheSamplingTable() throws Exception {
        capturedExport();

        assertThat(this.table.lookup(this.identity).map(ExporterSamplingTable.AdvertisedRate::interval))
                .as("softflowd states 1 packet in every 1+99; the table must hold 100")
                .contains(EXPECTED_RATE);
    }

    /** And the flows in the same export resolve against it. */
    @Test
    public void capturedFlowsResolveToTheAdvertisedRate() throws Exception {
        final Packet packet = capturedExport();

        final List<Flow> flows = builder().buildFlows(Instant.EPOCH, packet, this.identity).toList();

        assertThat(flows).as("the captured export carries flow records").isNotEmpty();
        assertThat(flows).allSatisfy(flow -> {
            assertThat(flow.getSamplingInterval())
                    .as("dropped, this read 1.0 and under-reported softflowd's volume a hundredfold")
                    .isEqualTo(EXPECTED_RATE);
            assertThat(flow.getSamplingProvenance())
                    .as("riptide computed the rate from stated parameters, so `derived`, not `options`")
                    .isEqualTo(Flow.SamplingProvenance.Derived);
        });
    }

    /** The algorithm travels with it, from IE 304 rather than the deprecated pair. */
    @Test
    public void theAdvertisedAlgorithmNamesTheSamplingMode() throws Exception {
        final Packet packet = capturedExport();

        assertThat(builder().buildFlows(Instant.EPOCH, packet, this.identity).toList())
                .allSatisfy(flow -> assertThat(flow.getSamplingAlgorithm())
                        .isEqualTo(Flow.SamplingAlgorithm.SystematicCountBasedSampling));
    }
}
