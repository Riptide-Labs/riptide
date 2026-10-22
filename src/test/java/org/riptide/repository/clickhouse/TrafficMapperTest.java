/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.ApplicationSource;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.TrafficSchema;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code traffic} row is the contract another collector has to reproduce, so each rule the
 * mapper applies is pinned here by the row it produces.
 */
class TrafficMapperTest {

    private static final Instant START = Instant.parse("2026-10-06T10:00:30Z");
    private static final Instant END = Instant.parse("2026-10-06T10:02:45Z");

    @Test
    void netflowAndIpfixCountersAreScaledByTheirRate() {
        final TrafficRow row = TrafficMapper.row(flow(Flow.FlowProtocol.IPFIX, 1500L, 3L, 1000.0));

        assertThat(row.getBytesReported()).isEqualTo(1500L);
        assertThat(row.getPacketsReported()).isEqualTo(3L);
        assertThat(row.getBytes()).isEqualTo(1_500_000L);
        assertThat(row.getPackets()).isEqualTo(3000L);
        assertThat(row.getSamplingRate()).isEqualTo(1000.0);
    }

    /**
     * The sFlow receiver stores {@code frame_length × rate} already, so the stored value is the
     * estimate and must not be scaled a second time; the reported pair divides it back out.
     */
    @Test
    void sflowCountersArriveScaledAndAreDividedBackForTheReportedPair() {
        final TrafficRow row = TrafficMapper.row(flow(Flow.FlowProtocol.SFLOW, 1500L * 512, 512L, 512.0));

        assertThat(row.getBytes()).isEqualTo(1500L * 512);
        assertThat(row.getPackets()).isEqualTo(512L);
        assertThat(row.getBytesReported()).isEqualTo(1500L);
        assertThat(row.getPacketsReported()).isEqualTo(1L);
    }

    /** One rule for every protocol: the estimate is the reported value times the stated rate. */
    @Test
    void bytesIsReportedTimesRateForEveryProtocol() {
        for (final Flow.FlowProtocol protocol : Flow.FlowProtocol.values()) {
            final long stored = protocol == Flow.FlowProtocol.SFLOW ? 64L * 100 : 64L;
            final TrafficRow row = TrafficMapper.row(flow(protocol, stored, 1L, 100.0));
            assertThat(row.getBytes())
                    .as(protocol.name())
                    .isEqualTo((long) (row.getBytesReported() * row.getSamplingRate()))
                    .isEqualTo(6400L);
        }
    }

    @Test
    void anUnsampledFlowKeepsItsCountersAndRecordsRateOne() {
        final TrafficRow row = TrafficMapper.row(flow(Flow.FlowProtocol.NetflowV9, 1500L, 3L, null));

        assertThat(row.getBytes()).isEqualTo(1500L);
        assertThat(row.getBytesReported()).isEqualTo(1500L);
        assertThat(row.getSamplingRate()).isEqualTo(1.0);
        assertThat(row.getSamplingSource()).isEqualTo("assumed");
    }

    /** A rate that is not above 1 states nothing to scale by, and the row says so. */
    @Test
    void aRateThatIsNoRateIsRecordedAsOne() {
        for (final double bogus : new double[] {0.0, -5.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            final TrafficRow row = TrafficMapper.row(flow(Flow.FlowProtocol.IPFIX, 1500L, 3L, bogus));
            assertThat(row.getSamplingRate()).as(Double.toString(bogus)).isEqualTo(1.0);
            assertThat(row.getBytes()).as(Double.toString(bogus)).isEqualTo(1500L);
        }
    }

    /** IPFIX selector parameters give 1 / probability, which need not be a whole number. */
    @Test
    void aFractionalRateRoundsToTheNearestByte() {
        assertThat(TrafficMapper.scale(1000L, 2.5)).isEqualTo(2500L);
        assertThat(TrafficMapper.scale(3L, 1.5)).isEqualTo(5L);
    }

    /**
     * Refused as {@code 0}, with the reported counter kept, so the refusal is findable and does not
     * swamp a sum the way a saturated value would.
     */
    @Test
    void anEstimateThatDoesNotFitIsRefusedNotSaturated() {
        final TrafficRow row = TrafficMapper.row(flow(Flow.FlowProtocol.IPFIX, Long.MAX_VALUE / 2, 1L, 4.0));

        assertThat(row.getBytes()).isZero();
        assertThat(row.getBytesReported()).isEqualTo(Long.MAX_VALUE / 2);
        assertThat(TrafficMapper.scale(Long.MAX_VALUE / 2, 4.5)).isZero();
        // A UInt64 counter above 2^63 arrives negative and has no room to scale.
        assertThat(TrafficMapper.scale(-1L, 2.0)).isZero();
    }

    @Test
    void theIntervalTheCountersCoverRunsFromDeltaSwitchedToLastSwitched() {
        final EnrichedFlow flow = flow(Flow.FlowProtocol.IPFIX, 1L, 1L, null);
        flow.setFirstSwitched(START.minusSeconds(600));

        final TrafficRow row = TrafficMapper.row(flow);

        assertThat(row.getTimeStart().toInstant()).isEqualTo(START);
        assertThat(row.getTimeEnd().toInstant()).isEqualTo(END);
        assertThat(row.getFlowStart().toInstant()).isEqualTo(START.minusSeconds(600));
        assertThat(row.getTimeReceived().toInstant()).isEqualTo(END.plusSeconds(1));
        assertThat(row.getTimeStart().getOffset().getTotalSeconds()).isZero();
    }

    @Test
    void missingTimesFallBackAndAFlowWithNoneIsNotFiled() {
        final EnrichedFlow flow = EnrichedFlow.builder().receivedAt(END).build();

        final TrafficRow row = TrafficMapper.row(flow);
        assertThat(row.getTimeStart().toInstant()).isEqualTo(END);
        assertThat(row.getTimeEnd().toInstant()).isEqualTo(END);
        assertThat(row.getFlowStart().toInstant()).isEqualTo(END);

        assertThat(TrafficMapper.row(EnrichedFlow.builder().build())).isNull();
    }

    /** No column is Nullable, so the client would refuse the whole insert on one null field. */
    @Test
    void anEmptyFlowStillLeavesNoFieldNull() throws Exception {
        final TrafficRow row = TrafficMapper.row(EnrichedFlow.builder().receivedAt(END).build());

        for (final Field field : contractFields()) {
            field.setAccessible(true);
            assertThat(field.get(row)).as(field.getName()).isNotNull();
        }
        assertThat(row.getSrcAddr().getHostAddress()).isEqualTo("0:0:0:0:0:0:0:0");
        assertThat(row.getExporterIp().getHostAddress()).isEqualTo("0:0:0:0:0:0:0:0");
        assertThat(row.getApplicationSource()).isEqualTo(ApplicationSource.None.token());
        assertThat(row.getDirection()).isEqualTo("unknown");
        assertThat(row.getFlowProtocol()).isEmpty();
        assertThat(row.getSrcLocality()).isEmpty();
        assertThat(row.getInIfName()).isEmpty();
    }

    @Test
    void tokensAreLowerCaseAndStable() {
        assertThat(Arrays.stream(Flow.FlowProtocol.values()).map(TrafficMapper::flowProtocol))
                .containsExactly("netflow5", "netflow9", "ipfix", "sflow");
        assertThat(Arrays.stream(Flow.Direction.values()).map(TrafficMapper::direction))
                .containsExactly("ingress", "egress", "unknown");
        assertThat(Arrays.stream(Flow.Locality.values()).map(TrafficMapper::locality))
                .containsExactly("public", "private");
    }

    @Test
    void addressesAreStoredIpv4MappedAndTheExporterIsNeverResolved() throws Exception {
        assertThat(TrafficMapper.address(InetAddress.getByName("192.0.2.10")).getAddress())
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 0, 2, 10);
        assertThat(TrafficMapper.literal("203.0.113.7").getAddress())
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 203, 0, 113, 7);
        assertThat(TrafficMapper.literal("2001:db8::1").getHostAddress()).isEqualTo("2001:db8:0:0:0:0:0:1");
        // A name is not a literal, and looking it up would be network I/O on the flow path.
        assertThat(TrafficMapper.literal("localhost").getHostAddress()).isEqualTo("0:0:0:0:0:0:0:0");
    }

    @Test
    void anAsNumberOutside32BitsReadsAsUnknownAndIfIndexIsUnsigned() {
        final EnrichedFlow flow = flow(Flow.FlowProtocol.IPFIX, 1L, 1L, null);
        flow.setSrcAs(0x1_0000_0000L);
        flow.setDstAs(4_200_000_000L);
        flow.setInputSnmp(-1);

        final TrafficRow row = TrafficMapper.row(flow);

        assertThat(row.getSrcAs()).isZero();
        assertThat(row.getDstAs()).isEqualTo(4_200_000_000L);
        assertThat(row.getInIf()).isEqualTo(0xFFFF_FFFFL);
    }

    /**
     * The row type and the table cannot drift: every field is a column and every column a field,
     * under the client's own matching rule (underscores dropped, case ignored).
     */
    @Test
    void everyRowFieldIsATrafficColumnAndEveryColumnAField() {
        final Set<String> fields = contractFields().stream()
                .map(field -> field.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        final Set<String> columns = TrafficSchema.trafficColumns().keySet().stream()
                .map(column -> column.replace("_", "").toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(fields).isEqualTo(columns);
    }

    private static List<Field> contractFields() {
        return Arrays.stream(TrafficRow.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .toList();
    }

    private static EnrichedFlow flow(final Flow.FlowProtocol protocol, final long bytes, final long packets,
                                     final Double rate) {
        return EnrichedFlow.builder()
                .receivedAt(END.plusSeconds(1))
                .timestamp(END)
                .firstSwitched(START)
                .deltaSwitched(START)
                .lastSwitched(END)
                .flowProtocol(protocol)
                .bytes(bytes)
                .packets(packets)
                .samplingInterval(rate)
                .samplingProvenance(rate == null ? Flow.SamplingProvenance.Assumed : Flow.SamplingProvenance.Record)
                .build();
    }
}
