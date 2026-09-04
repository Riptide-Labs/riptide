/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The dead-letter payload's one contract: what goes in comes back out (#548).
 *
 * <p>Named in the spec's Block-If — "serialising a flow into the dead-letter payload cannot be shown
 * to round-trip" — because a payload that cannot be read back is not a dead letter, it is a slower
 * drop. Everything else about the format is a decision; this is the property.
 */
class DeadLetterPayloadTest {

    /**
     * Every field of {@link EnrichedFlow} survives the round trip.
     *
     * <p>The guard runs first and is the half that keeps this honest: an equality assertion over a
     * flow with three fields set passes on a codec that drops the other fifty. {@link #everyFieldSet}
     * fails the build the moment a field is added to {@link EnrichedFlow} without being given a value
     * here — so the payload cannot silently grow a hole.</p>
     */
    @Test
    void everyFieldOfAFullyPopulatedFlowSurvivesTheRoundTrip() throws Exception {
        final EnrichedFlow original = fullyPopulated();
        everyFieldSet(original);

        final EnrichedFlow restored = DeadLetterPayload.deserialise(DeadLetterPayload.serialise(original));

        Assertions.assertThat(restored)
                .as("a dead letter that cannot be read back is a slower drop, not a rescue")
                .isEqualTo(original);
    }

    /**
     * Nanosecond precision survives, which the {@code flows} table's own columns declare
     * ({@code DateTime64(9)} on {@code receivedAt} and the switched timestamps).
     *
     * <p>Separate from the round trip above because that fixture stamps whole milliseconds, and a
     * codec that truncated to millis — the obvious mistake, since {@code failedAt} genuinely is
     * millisecond-scaled — would pass it.</p>
     */
    @Test
    void subMillisecondPrecisionIsNotTruncated() throws Exception {
        final Instant precise = Instant.parse("2026-09-04T10:11:12Z").plusNanos(123_456_789L);
        final EnrichedFlow original = fullyPopulatedBuilder().receivedAt(precise).build();

        Assertions.assertThat(DeadLetterPayload.deserialise(DeadLetterPayload.serialise(original)))
                .extracting(EnrichedFlow::getReceivedAt)
                .isEqualTo(precise);
    }

    /**
     * The provenance rung travels as its stored token, not as its Java constant name.
     *
     * <p>{@code Flow.SamplingProvenance}'s own javadoc makes the token the stable identifier —
     * renaming a constant must not rewrite what stored rows mean — and a payload sitting under a TTL
     * in ClickHouse is a stored row. Jackson's default would write {@code "Options"}; this asserts
     * the wire actually carries {@code "options"}, which no equality assertion could tell apart.</p>
     */
    @Test
    void theProvenanceRungIsStoredAsItsTokenRatherThanItsConstantName() throws Exception {
        final EnrichedFlow flow = fullyPopulatedBuilder()
                .samplingProvenance(Flow.SamplingProvenance.Options)
                .build();

        final String json = DeadLetterPayload.serialise(flow);

        Assertions.assertThat(new ObjectMapper().readTree(json).get("samplingProvenance").asText())
                .as("the token is the stable identifier; the constant name is free to be renamed")
                .isEqualTo("options");
        Assertions.assertThat(DeadLetterPayload.deserialise(json).getSamplingProvenance())
                .isEqualTo(Flow.SamplingProvenance.Options);
    }

    /**
     * An address is written as its numeric literal, never as {@code toString()}'s
     * {@code <host>/<literal>}.
     *
     * <p>Pinned because the difference is invisible to the round trip: Jackson's built-in
     * serialiser strips the hostname back off on the way out, so a payload carrying whatever
     * hostname happened to be cached would still deserialise equal — while every stored row leaked a
     * resolved name nobody asked for.</p>
     */
    @Test
    void anAddressIsStoredAsItsLiteralWithNoResolvedHostname() throws Exception {
        final EnrichedFlow flow = fullyPopulatedBuilder()
                .srcAddr(InetAddress.getByAddress("cached.example.org", new byte[] {(byte) 192, 0, 2, 10}))
                .build();

        final JsonNode json = new ObjectMapper().readTree(DeadLetterPayload.serialise(flow));

        Assertions.assertThat(json.get("srcAddr").asText()).isEqualTo("192.0.2.10");
    }

    /**
     * One JSON line per flow, each carrying its own tenant — the shape {@code JSONEachRow} needs and
     * the reason a batch spanning tenants can be row-policied at all.
     */
    @Test
    void aBatchSpanningTenantsBecomesOneLinePerFlowEachWithItsOwnTenant() throws Exception {
        final EnrichedFlow acme = fullyPopulatedBuilder().tenant("acme").build();
        final EnrichedFlow globex = fullyPopulatedBuilder().tenant("globex").build();

        final String body = new String(
                DeadLetterPayload.jsonEachRow(List.of(acme, globex),
                        Instant.parse("2026-09-04T10:11:12.345Z"), new IllegalStateException("refused")),
                StandardCharsets.UTF_8);

        final List<String> lines = body.lines().toList();
        Assertions.assertThat(lines).hasSize(2);
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode first = mapper.readTree(lines.get(0));
        Assertions.assertThat(first.get("tenant").asText()).isEqualTo("acme");
        Assertions.assertThat(mapper.readTree(lines.get(1)).get("tenant").asText()).isEqualTo("globex");
        // ClickHouse's date_time_input_format = 'basic' takes this form and rejects an ISO-8601
        // instant, so the exact rendering is the contract rather than a formatting preference.
        Assertions.assertThat(first.get("failedAt").asText()).isEqualTo("2026-09-04 10:11:12.345");
        Assertions.assertThat(first.get("error").asText()).contains("refused");
        // The payload is a nested JSON *string*, escaped by the same mapper that wrote the row —
        // and it must still parse back into the flow it came from.
        Assertions.assertThat(DeadLetterPayload.deserialise(first.get("payload").asText()))
                .isEqualTo(acme);
    }

    /**
     * A flow with no tenant writes {@code ''} rather than {@code null}.
     *
     * <p>{@code tenant} is a non-nullable {@code String} column, so a JSON {@code null} would be
     * refused — and the dead-letter insert is the one write that must not fail for a reason of its
     * own making.</p>
     */
    @Test
    void aFlowWithNoTenantStillProducesARowRatherThanARefusedInsert() throws Exception {
        final String body = new String(
                DeadLetterPayload.jsonEachRow(List.of(EnrichedFlow.builder().srcPort(1).build()),
                        Instant.EPOCH, null),
                StandardCharsets.UTF_8);

        final JsonNode row = new ObjectMapper().readTree(body.lines().findFirst().orElseThrow());
        Assertions.assertThat(row.get("tenant").asText()).isEmpty();
        Assertions.assertThat(row.get("error").asText()).isEmpty();
    }

    /**
     * Every declared instance field of {@link EnrichedFlow} carries a value.
     *
     * <p>Reflective on purpose. A hand-written list of fields would be a second place remembering
     * the class's shape, and the one that drifts is the one nothing runs: a field added to
     * {@link EnrichedFlow} and forgotten here would leave the round-trip assertion comparing two
     * nulls and passing.</p>
     */
    private static void everyFieldSet(final EnrichedFlow flow) throws Exception {
        for (final Field field : EnrichedFlow.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            final Object value = field.get(flow);
            Assertions.assertThat(value)
                    .as("EnrichedFlow.%s is not populated by this test's fixture, so the round-trip"
                            + " assertion would compare two empty values and pass on a codec that"
                            + " drops it", field.getName())
                    .isNotNull();
            if (value instanceof Number number) {
                // The primitives (flowRecords, flowSeqNum) read as 0 rather than null when unset, so
                // non-null says nothing about them. Every numeric in the fixture below is deliberately
                // non-zero, which makes "unset" and "set" distinguishable for all of them alike.
                Assertions.assertThat(number.doubleValue())
                        .as("EnrichedFlow.%s is left at its type default, which is indistinguishable"
                                + " from a codec dropping it", field.getName())
                        .isNotEqualTo(0.0);
            }
        }
    }

    /**
     * A flow with every field set to a distinct, non-default value.
     *
     * <p>Not {@code ClickhouseItFlows.flow}: that fixture populates what the {@code flows} table
     * needs, which is deliberately less than every field, and the guard above requires all of
     * them.</p>
     */
    private static EnrichedFlow fullyPopulated() throws Exception {
        return fullyPopulatedBuilder().build();
    }

    /** As {@link #fullyPopulated()}, unbuilt, so one field can be overridden without a copy. */
    private static EnrichedFlow.EnrichedFlowBuilder fullyPopulatedBuilder() throws Exception {
        final Instant now = Instant.parse("2026-09-04T10:11:12.345Z").truncatedTo(ChronoUnit.MILLIS);
        return EnrichedFlow.builder()
                .receivedAt(now)
                .timestamp(now.minusSeconds(1))
                .bytes(1234L)
                .direction(Flow.Direction.INGRESS)
                .dstAddr(InetAddress.getByName("198.51.100.20"))
                .dstAddrHostname("dst.example.org")
                .dstAs(64513L)
                .dstAsOrg("Globex")
                .dstMaskLen(24)
                .dstPort(443)
                .engineId(3)
                .engineType(5)
                .deltaSwitched(now.minusSeconds(10))
                .firstSwitched(now.minusSeconds(11))
                .flowRecords(7)
                .flowSeqNum(99L)
                .inputSnmp(1)
                .ipProtocolVersion(4)
                .lastSwitched(now.minusSeconds(2))
                .nextHop(InetAddress.getByName("2001:db8::1"))
                .nextHopHostname("hop.example.org")
                .outputSnmp(2)
                .packets(11L)
                .protocol(17)
                .samplingAlgorithm(Flow.SamplingAlgorithm.RandomNOutOfNSampling)
                .samplingInterval(64.0)
                .samplingProvenance(Flow.SamplingProvenance.Record)
                .srcAddr(InetAddress.getByName("192.0.2.10"))
                .srcAddrHostname("src.example.org")
                .srcAs(64512L)
                .srcAsOrg("Acme")
                .srcMaskLen(25)
                .srcPort(20001)
                .tcpFlags(2)
                .tos(184)
                .flowProtocol(Flow.FlowProtocol.IPFIX)
                .vlan(42)
                .application("https")
                .exporterAddr("203.0.113.7")
                .tenant("acme")
                .organisation("org")
                .zone("default")
                .system("default")
                .srcLocality(Flow.Locality.PUBLIC)
                .dstLocality(Flow.Locality.PRIVATE)
                .flowLocality(Flow.Locality.PUBLIC)
                .clockCorrection(Duration.ofMillis(-250))
                .inputSnmpIfName("ge-0/0/1")
                .inputSnmpIfAlias("uplink")
                .inputSnmpIfSpeed(1_000_000_000L)
                .outputSnmpIfName("ge-0/0/2")
                .outputSnmpIfAlias("downlink")
                .outputSnmpIfSpeed(10_000_000_000L)
                .srcCountry("DE")
                .srcCity("Fulda")
                .dstCountry("US")
                .dstCity("Austin")
                .exporterName("edge-01");
    }
}
