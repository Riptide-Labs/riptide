package org.riptide.flows.parser.data;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
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
    default Instant getDeltaSwitched() {
        return this.getFirstSwitched();
    }

    /**
     * Unix timestamp in ms at which the first packet
     * associated with this flow was switched.
     */
    Instant getFirstSwitched();

    /**
     * Number of flow records in the associated packet.
     */
    int getFlowRecordNum();

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
}
