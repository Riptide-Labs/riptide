package org.riptide.flows.parser.ipfix;

import lombok.Getter;

import java.net.InetAddress;
import java.time.Instant;

/// DTO object for Ipfix flows. This contains all the fields/scopes/options we want to use internally
/// This object is required to make conversion to an actual [org.riptide.flows.parser.data.Flow] easier.
@Getter
public class IpfixRawFlow {
    public long sequenceNumber;
    public long exportTime;
    public long observationDomainId;
    public Long octetDeltaCount;
    public Long postOctetDeltaCount;
    public Long layer2OctetDeltaCount;
    public Long postLayer2OctetDeltaCount;
    public Long transportOctetDeltaCount;
    public Long bgpDestinationAsNumber;
    public Integer flowDirection;
    public InetAddress destinationIPv6Address;
    public InetAddress destinationIPv4Address;
    Integer destinationIPv6PrefixLength;
    Integer destinationIPv4PrefixLength;
    Integer destinationTransportPort;
    Integer engineId;
    Integer engineType;
    Long flowActiveTimeout;
    Long flowInactiveTimeout;
    Instant flowStartSeconds;
    Instant flowStartMilliseconds;
    Instant flowStartMicroseconds;
    Instant flowStartNanoseconds;
    Long flowStartDeltaMicroseconds;
    Long flowStartSysUpTime;
    Instant flowEndSeconds;
    Instant flowEndMilliseconds;
    Instant flowEndMicroseconds;
    Long flowEndNanoseconds;
    Long flowEndSysUpTime;
    Integer ingressPhysicalInterface;
    Integer ingressInterface;
    Integer ipVersion;
    InetAddress ipNextHopIPv6Address;
    InetAddress ipNextHopIPv4Address;
    InetAddress bgpNextHopIPv6Address;
    InetAddress bgpNextHopIPv4Address;
    Integer egressPhysicalInterface;
    Integer egressInterface;
    Long packetDeltaCount;
    Long postPacketDeltaCount;
    Long transportPacketDeltaCount;
    Integer protocolIdentifier;
    Integer samplingAlgorithm;
    Integer samplerMode;
    Integer selectorAlgorithm;
    Double samplingInterval;
    Double samplerRandomInterval;
    Double samplingFlowInterval;
    Double flowSamplingTimeInterval;
    Double samplingFlowSpacing;
    Double flowSamplingTimeSpacing;
    Double samplingSize;
    Double samplingPopulation;
    Double samplingProbability;
    // TODO MVR
//    Void hashSelectedRangeMin;
//    Void hashSelectedRangeMax;
//    Void hashOutputRangeMin;
//    Void hashOutputRangeMax;
    InetAddress sourceIPv6Address;
    InetAddress sourceIPv4Address;
    Long bgpSourceAsNumber;
    Integer sourceIPv6PrefixLength;
    Integer sourceIPv4PrefixLength;
    Integer sourceTransportPort;
    Integer tcpControlBits;
    Integer ipClassOfService;
    Integer vlanId;
    Integer postVlanId;
    Integer dot1qVlanId;
    Integer dot1qCustomerVlanId;
    Integer postDot1qVlanId;
    Integer postDot1qCustomerVlanId;
}
