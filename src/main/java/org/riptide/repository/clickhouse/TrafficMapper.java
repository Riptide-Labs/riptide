/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.ApplicationSource;
import org.riptide.pipeline.EnrichedFlow;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Builds a {@code traffic} row from an enriched flow. Every rule that turns what a protocol reports
 * into what a dashboard can sum lives here, written out by hand rather than generated, because this
 * class is the specification another collector has to reproduce row for row.
 *
 * <p>Unknown is {@code ''} for text, {@code 0} for numbers and {@code ::} for addresses. Nothing is
 * left null, because no {@code traffic} column is {@code Nullable}.</p>
 */
public final class TrafficMapper {

    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    private static final Inet6Address UNSPECIFIED = unspecified();

    private TrafficMapper() {
    }

    /**
     * The row for {@code flow}, or {@code null} when the flow carries no time at all, which a row
     * cannot be filed without. The receiver stamps {@code receivedAt} on every flow, so in practice
     * that is a flow built by hand.
     */
    public static TrafficRow row(final EnrichedFlow flow) {
        final Instant received = first(flow.getReceivedAt(), flow.getTimestamp());
        // The interval these counters cover. deltaSwitched is its start: for a long-lived flow under
        // an active timeout it is the previous export, not the flow's first packet.
        final Instant end = first(flow.getLastSwitched(), flow.getTimestamp(), received);
        final Instant start = first(flow.getDeltaSwitched(), flow.getFirstSwitched(), end);
        if (start == null) {
            return null;
        }

        final TrafficRow row = new TrafficRow();
        row.setTenant(text(flow.getTenant()));
        row.setOrganisation(text(flow.getOrganisation()));
        row.setZone(text(flow.getZone()));
        row.setSystem(text(flow.getSystem()));

        row.setTimeStart(utc(start));
        row.setTimeEnd(utc(end));
        row.setFlowStart(utc(first(flow.getFirstSwitched(), start)));
        row.setTimeReceived(utc(first(received, end)));

        row.setExporterIp(literal(flow.getExporterAddr()));
        row.setExporterName(text(flow.getExporterName()));
        row.setFlowProtocol(flowProtocol(flow.getFlowProtocol()));
        row.setObservationDomain(unsigned(flow.getEngineId()));

        row.setInIf(unsigned(flow.getInputSnmp()));
        row.setInIfName(text(flow.getInputSnmpIfName()));
        row.setInIfAlias(text(flow.getInputSnmpIfAlias()));
        row.setInIfSpeed(number(flow.getInputSnmpIfSpeed()));
        row.setOutIf(unsigned(flow.getOutputSnmp()));
        row.setOutIfName(text(flow.getOutputSnmpIfName()));
        row.setOutIfAlias(text(flow.getOutputSnmpIfAlias()));
        row.setOutIfSpeed(number(flow.getOutputSnmpIfSpeed()));
        row.setDirection(direction(flow.getDirection()));

        row.setSrcAddr(address(flow.getSrcAddr()));
        row.setSrcPort(number(flow.getSrcPort()));
        row.setSrcMask(number(flow.getSrcMaskLen()));
        row.setSrcAs(asn(flow.getSrcAs()));
        row.setSrcAsName(text(flow.getSrcAsOrg()));
        row.setSrcCountry(text(flow.getSrcCountry()));
        row.setSrcCity(text(flow.getSrcCity()));
        row.setSrcHost(text(flow.getSrcAddrHostname()));
        row.setSrcLocality(locality(flow.getSrcLocality()));
        row.setDstAddr(address(flow.getDstAddr()));
        row.setDstPort(number(flow.getDstPort()));
        row.setDstMask(number(flow.getDstMaskLen()));
        row.setDstAs(asn(flow.getDstAs()));
        row.setDstAsName(text(flow.getDstAsOrg()));
        row.setDstCountry(text(flow.getDstCountry()));
        row.setDstCity(text(flow.getDstCity()));
        row.setDstHost(text(flow.getDstAddrHostname()));
        row.setDstLocality(locality(flow.getDstLocality()));
        row.setNextHop(address(flow.getNextHop()));

        row.setIpVersion(number(flow.getIpProtocolVersion()));
        row.setProto(number(flow.getProtocol()));
        row.setTcpFlags(number(flow.getTcpFlags()));
        row.setTos(number(flow.getTos()));
        row.setVlan(number(flow.getVlan()));

        row.setApplication(text(flow.getApplication()));
        row.setApplicationSource(flow.getApplicationSource() != null
                ? flow.getApplicationSource().token() : ApplicationSource.None.token());
        row.setApplicationId(uint32(flow.getApplicationId()));
        row.setHttpHost(text(flow.getHttpHost()));
        row.setHttpUri(text(flow.getHttpUri()));

        volume(row, flow);
        return row;
    }

    /**
     * {@code bytes} and {@code packets} are the estimate on the wire for every protocol; the
     * {@code _reported} pair is what the exporter sent; {@code sampling_rate} is the factor between
     * them, so {@code bytes = bytes_reported × sampling_rate} holds on every row but an overflowed
     * one (see {@link #scale}).
     *
     * <p>The two directions exist because the protocols disagree. NetFlow and IPFIX report the
     * sampled counters and state the rate beside them, so the estimate is computed here. sFlow's
     * receiver already scales at ingest ({@code frame_length × sampling_rate}, see
     * {@code SflowFlowBuilder}), so the stored value is the estimate and the reported one is
     * recovered by dividing it back out.</p>
     */
    static void volume(final TrafficRow row, final EnrichedFlow flow) {
        final double rate = rate(flow.getSamplingInterval());
        final long bytes = number(flow.getBytes());
        final long packets = number(flow.getPackets());
        row.setSamplingRate(rate);
        row.setSamplingSource(flow.getSamplingProvenance() != null
                ? flow.getSamplingProvenance().token() : Flow.SamplingProvenance.Assumed.token());
        if (flow.getFlowProtocol() == Flow.FlowProtocol.SFLOW) {
            row.setBytes(bytes);
            row.setPackets(packets);
            row.setBytesReported(unscale(bytes, rate));
            row.setPacketsReported(unscale(packets, rate));
        } else {
            row.setBytesReported(bytes);
            row.setPacketsReported(packets);
            row.setBytes(scale(bytes, rate));
            row.setPackets(scale(packets, rate));
        }
    }

    /**
     * The factor the counters are scaled by. Anything that is not a rate above 1 (absent, {@code 0},
     * negative, {@code NaN}) means the counters are taken as they are, and is recorded as {@code 1}
     * so the row's own columns state the factor that was applied.
     */
    static double rate(final Double interval) {
        return interval != null && interval > 1.0 && !interval.isInfinite() ? interval : 1.0;
    }

    /**
     * {@code reported × rate}, or {@code 0} when the product does not fit in 63 bits.
     *
     * <p><b>Refused, not saturated.</b> A saturated value is no more a measurement than a wrapped
     * one, and in any {@code sum()} it swamps every real row beside it. This matches what
     * {@code SflowFlowBuilder} does with a frame length it cannot scale. The row keeps its
     * {@code bytes_reported}, so {@code WHERE bytes = 0 AND bytes_reported > 0} finds every refusal.
     * A {@code reported} that is negative here is a {@code UInt64} counter above 2^63, which has no
     * room to scale at all.</p>
     */
    static long scale(final long reported, final double rate) {
        if (rate == 1.0 || reported == 0) {
            return reported;
        }
        if (reported < 0) {
            return 0;
        }
        if (rate == Math.rint(rate) && rate < 0x1p63) {
            // Integer rates, which is nearly all of them, stay in exact integer arithmetic.
            try {
                return Math.multiplyExact(reported, (long) rate);
            } catch (final ArithmeticException overflow) {
                return 0;
            }
        }
        // A fractional rate: IPFIX selector parameters give 1 / probability.
        final double product = reported * rate;
        return product < 0x1p63 ? Math.round(product) : 0;
    }

    /** {@code scaled ÷ rate}: the counter an sFlow agent sent, before the receiver scaled it. */
    static long unscale(final long scaled, final double rate) {
        if (rate == 1.0) {
            return scaled;
        }
        if (rate == Math.rint(rate) && rate < 0x1p63) {
            return Long.divideUnsigned(scaled, (long) rate);
        }
        // sFlow rates are integers on the wire; this serves only a rate built by hand.
        return Math.round(scaled / rate);
    }

    /** Lower-case protocol tokens, stable whatever the Java constants are called. */
    static String flowProtocol(final Flow.FlowProtocol protocol) {
        if (protocol == null) {
            return "";
        }
        return switch (protocol) {
            case NetflowV5 -> "netflow5";
            case NetflowV9 -> "netflow9";
            case IPFIX -> "ipfix";
            case SFLOW -> "sflow";
        };
    }

    static String direction(final Flow.Direction direction) {
        if (direction == null) {
            return "unknown";
        }
        return switch (direction) {
            case INGRESS -> "ingress";
            case EGRESS -> "egress";
            case UNKNOWN -> "unknown";
        };
    }

    static String locality(final Flow.Locality locality) {
        if (locality == null) {
            return "";
        }
        return switch (locality) {
            case PUBLIC -> "public";
            case PRIVATE -> "private";
        };
    }

    /** An AS number is 32 bits by definition; anything outside that is not one, and reads as unknown. */
    static long asn(final Long value) {
        return uint32(value);
    }

    private static long uint32(final Long value) {
        return value != null && value >= 0 && value <= UINT32_MAX ? value : 0;
    }

    /** A {@code uint32} the parser carried in a signed {@code int}. */
    private static long unsigned(final Integer value) {
        return value != null ? Integer.toUnsignedLong(value) : 0;
    }

    private static int number(final Integer value) {
        return value != null ? value : 0;
    }

    private static long number(final Long value) {
        return value != null ? value : 0;
    }

    private static String text(final String value) {
        return value != null ? value : "";
    }

    @SafeVarargs
    private static <T> T first(final T... values) {
        for (final T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static OffsetDateTime utc(final Instant value) {
        // An offset-carrying type so the client encodes an absolute instant whatever the host's
        // timezone is (#276).
        return value.atOffset(ZoneOffset.UTC);
    }

    /** The exporter's address as the receiver recorded it: a literal, never a name to resolve. */
    static Inet6Address literal(final String value) {
        if (value == null || value.isEmpty()) {
            return UNSPECIFIED;
        }
        try {
            return address(InetAddress.ofLiteral(value));
        } catch (final IllegalArgumentException notALiteral) {
            return UNSPECIFIED;
        }
    }

    /** IPv4 as IPv4-mapped IPv6, as every address column stores it; absent as {@code ::}. */
    static Inet6Address address(final InetAddress value) {
        if (value instanceof Inet6Address v6) {
            return v6;
        }
        if (value instanceof Inet4Address v4) {
            final byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xff;
            mapped[11] = (byte) 0xff;
            System.arraycopy(v4.getAddress(), 0, mapped, 12, 4);
            return v6(mapped);
        }
        return UNSPECIFIED;
    }

    private static Inet6Address unspecified() {
        return v6(new byte[16]);
    }

    private static Inet6Address v6(final byte[] address) {
        try {
            return Inet6Address.getByAddress(null, address, null);
        } catch (final UnknownHostException impossible) {
            // Only thrown for an array of the wrong length, and both callers pass 16 bytes.
            throw new IllegalStateException(impossible);
        }
    }
}
