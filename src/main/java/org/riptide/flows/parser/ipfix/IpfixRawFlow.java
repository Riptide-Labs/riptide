package org.riptide.flows.parser.ipfix;

import com.google.common.primitives.UnsignedLong;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

/// DTO object for Ipfix flows. This contains all the fields/scopes/options we want to use internally
/// This object is required to make conversion to an actual [org.riptide.flows.parser.data.Flow] easier.
public class IpfixRawFlow {
    public int recordCount;
    public long sequenceNumber;
    public Instant exportTime;
    public Instant systemInitTimeMilliseconds;
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
    public Integer destinationIPv6PrefixLength;
    public Integer destinationIPv4PrefixLength;
    public Integer destinationTransportPort;
    public Integer engineId;
    public Integer engineType;
    public Duration flowActiveTimeout;
    public Duration flowInactiveTimeout;
    public Instant flowStartSeconds;
    public Instant flowStartMilliseconds;
    public Instant flowStartMicroseconds;
    public Instant flowStartNanoseconds;
    public Duration flowStartDeltaMicroseconds;
    public Duration flowStartSysUpTime;
    public Instant flowEndSeconds;
    public Instant flowEndMilliseconds;
    public Instant flowEndMicroseconds;
    public Instant flowEndNanoseconds;
    public Duration flowEndDeltaMicroseconds;
    public Duration flowEndSysUpTime;
    public Integer ingressPhysicalInterface;
    public Integer ingressInterface;
    public Integer ipVersion;
    public InetAddress ipNextHopIPv6Address;
    public InetAddress ipNextHopIPv4Address;
    public InetAddress bgpNextHopIPv6Address;
    public InetAddress bgpNextHopIPv4Address;
    public Integer egressPhysicalInterface;
    public Integer egressInterface;
    public Long packetDeltaCount;
    public Long postPacketDeltaCount;
    public Long transportPacketDeltaCount;
    public Integer protocolIdentifier;
    public Integer samplingAlgorithm;
    public Integer samplerMode;
    public Integer selectorAlgorithm;
    public Double samplingInterval;
    public Double samplerRandomInterval;
    public Double samplingFlowInterval;
    public Double flowSamplingTimeInterval;
    public Double samplingFlowSpacing;
    public Double flowSamplingTimeSpacing;
    public Double samplingSize;
    public Double samplingPopulation;
    public Double samplingProbability;
    public UnsignedLong hashSelectedRangeMin;
    public UnsignedLong hashSelectedRangeMax;
    public UnsignedLong hashOutputRangeMin;
    public UnsignedLong hashOutputRangeMax;
    public InetAddress sourceIPv6Address;
    public InetAddress sourceIPv4Address;
    public Long bgpSourceAsNumber;
    public Integer sourceIPv6PrefixLength;
    public Integer sourceIPv4PrefixLength;
    public Integer sourceTransportPort;
    public Integer tcpControlBits;
    public Integer ipClassOfService;
    public Integer vlanId;
    public Integer postVlanId;
    public Integer dot1qVlanId;
    public Integer dot1qCustomerVlanId;
    public Integer postDot1qVlanId;
    public Integer postDot1qCustomerVlanId;

}
