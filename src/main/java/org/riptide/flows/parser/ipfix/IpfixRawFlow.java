package org.riptide.flows.parser.ipfix;

import com.google.common.primitives.UnsignedLong;
import lombok.Getter;

import java.net.InetAddress;
import java.time.Duration;
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
    Duration flowActiveTimeout;
    Duration flowInactiveTimeout;
    Instant flowStartSeconds;
    Instant flowStartMilliseconds;
    Instant flowStartMicroseconds;
    Instant flowStartNanoseconds;
    Instant flowStartDeltaMicroseconds;
    Instant flowStartSysUpTime;
    Instant flowEndSeconds;
    Instant flowEndMilliseconds;
    Instant flowEndMicroseconds;
    Instant flowEndDeltaMicroseconds;
    Instant flowEndNanoseconds;
    Instant flowEndSysUpTime;
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
    UnsignedLong hashSelectedRangeMin;
    UnsignedLong hashSelectedRangeMax;
    UnsignedLong hashOutputRangeMin;
    UnsignedLong hashOutputRangeMax;
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
