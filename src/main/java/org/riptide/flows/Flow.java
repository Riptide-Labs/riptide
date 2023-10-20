package org.riptide.flows;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface Flow {

    /**
     * Time at which the flow was received by listener in milliseconds since epoch UTC.
     */
    Instant getReceivedAt();

    /**
     * Flow timestamp in milliseconds.
     */
    Instant getTimestamp();

    /**
     * Number of bytes transferred in the flow.
     */
    Long getNumBytes();

    /**
     * Direction of the flow (egress vs ingress)
     */
    Direction getDirection();

    /**
     * Destination address.
     */
    InetAddress getDstAddr();

    /**
     * Destination address hostname.
     */
    Optional<String> getDstAddrHostname();

    /**
     * Destination autonomous system (AS).
     */
    Long getDstAs();

    /**
     * The number of contiguous bits in the source address subnet mask.
     */
    Integer getDstMaskLen();

    /**
     * Destination port.
     */
    Integer getDstPort();

    /**
     * Slot number of the flow-switching engine.
     */
    Integer getEngineId();

    /**
     * Type of flow-switching engine.
     */
    Integer getEngineType();

    /**
     * Unix timestamp in ms at which the previous exported packet
     * associated with this flow was switched.
     */
    Instant getDeltaSwitched();

    /**
     * Unix timestamp in ms at which the first packet
     * associated with this flow was switched.
     */
    Instant getFirstSwitched();

    /**
     * Number of flow records in the associated packet.
     */
    int getFlowRecords();

    /**
     * Flow packet sequence number.
     */
    long getFlowSeqNum();

    /**
     * SNMP ifIndex
     */
    Integer getInputSnmp();

    /**
     * IPv4 vs IPv6
     */
    Integer getIpProtocolVersion();

    /**
     * Unix timestamp in ms at which the last packet
     * associated with this flow was switched.
     */
    Instant getLastSwitched();

    /**
     * Next hop
     */
    InetAddress getNextHop();

    /**
     * Next hop hostname
     */
    Optional<String> getNextHopHostname();

    /**
     * SNMP ifIndex
     */
    Integer getOutputSnmp();

    /**
     * Number of packets in the flow
     */
    Long getNumPackets();

    /**
     * IP protocol number i.e 6 for TCP, 17 for UDP
     */
    Integer getProtocol();

    /**
     * Sampling algorithm ID
     */
    SamplingAlgorithm getSamplingAlgorithm();

    /**
     * Sampling interval
     */
    Double getSamplingInterval();

    /**
     * Source address.
     */
    InetAddress getSrcAddr();

    /**
     * Source address hostname.
     */
    Optional<String> getSrcAddrHostname();

    /**
     * Source autonomous system (AS).
     */
    Long getSrcAs();

    /**
     * The number of contiguous bits in the destination address subnet mask.
     */
    Integer getSrcMaskLen();

    /**
     * Source port.
     */
    Integer getSrcPort();

    /**
     * TCP Flags.
     */
    Integer getTcpFlags();

    /**
     * TOS.
     */
    Integer getTos();

    default Integer getDscp() {
        return getTos() != null ? getTos() >>> 2 : null;
    }

    default Integer getEcn() {
        return getTos() != null ? getTos() & 0x03 : null;
    }

    /**
     * Netfow version
     */
    NetflowVersion getNetflowVersion();

    /**
     * VLAN ID.
     */
    Integer getVlan();

//    /**
//     * Method to get node lookup identifier.
//     *
//     * This field can be used as an alternate means to identify the
//     * exporter node when the source address of the packets are altered
//     * due to address translation.
//     *
//     * * @return the identifier
//     */
//    String getNodeIdentifier();

    enum Locality {
        PUBLIC, PRIVATE
    }

    interface NodeInfo {
        int getInterfaceId();

        int getNodeId();

        String getForeignId();

        String getForeignSource();

        List<String> getCategories();
    }

    enum NetflowVersion {
        V5,
        V9,
        IPFIX,
        SFLOW,
    }

    enum Direction {
        INGRESS,
        EGRESS,
        UNKNOWN,
    }

    enum SamplingAlgorithm {
        Unassigned,
        SystematicCountBasedSampling,
        SystematicTimeBasedSampling,
        RandomNOutOfNSampling,
        UniformProbabilisticSampling,
        PropertyMatchFiltering,
        HashBasedFiltering,
        FlowStateDependentIntermediateFlowSelectionProcess;
    }

    class Switched {
        public final Instant firstSwitched;
        public final Instant lastSwitched;
        public final Instant deltaSwitched;

        private Switched(final Builder builder) {
            this.firstSwitched = Objects.requireNonNull(builder.firstSwitched);
            this.lastSwitched = Objects.requireNonNull(builder.lastSwitched);
            this.deltaSwitched = Objects.requireNonNull(builder.deltaSwitched);
        }

        public static class Builder {
            private Instant firstSwitched;
            private Instant lastSwitched;
            private Instant deltaSwitched;

            private Builder() {}

            public Builder withFirstSwitched(final Instant firstSwitched) {
                this.firstSwitched = firstSwitched;
                return this;
            }

            public Builder withLastSwitched(final Instant lastSwitched) {
                this.lastSwitched = lastSwitched;
                return this;
            }

            public Builder withDeltaSwitched(final Instant deltaSwitched) {
                this.deltaSwitched = deltaSwitched;
                return this;
            }

            public Switched build() {
                return new Switched(this);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

    }
}
