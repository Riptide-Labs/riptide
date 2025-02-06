package org.riptide.flows.parser.data;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.net.InetAddress;
import java.time.Instant;

@Getter
@Builder
public class Flow {
    @NonNull private Instant receivedAt;

    @NonNull private Instant timestamp;

    @NonNull private FlowProtocol flowProtocol;
    private int flowRecords;
    private long flowSeqNum;

    private Instant firstSwitched;
    private Instant deltaSwitched;
    private Instant lastSwitched;

    private int inputSnmp;
    private int outputSnmp;

    private long srcAs;
    private InetAddress srcAddr;
    private int srcMaskLen;
    private int srcPort;

    private long dstAs;
    private InetAddress dstAddr;
    private int dstMaskLen;
    private int dstPort;

    private InetAddress nextHop;

    private long bytes;
    private long packets;

    @NonNull private Direction direction;

    private int engineId;
    private int engineType;

    private int vlan;
    private int ipProtocolVersion;
    private int protocol;
    private int tcpFlags;
    private int tos;

    private SamplingAlgorithm samplingAlgorithm;
    private double samplingInterval;

    public Instant getDeltaSwitched() {
        return this.deltaSwitched != null
                ? this.deltaSwitched
                : this.firstSwitched;
    }

    public enum Locality {
        PUBLIC, PRIVATE
    }

    public enum FlowProtocol {
        NetflowV5,
        NetflowV9,
        IPFIX,
        SFLOW,
    }

    public enum Direction {
        INGRESS,
        EGRESS,
        UNKNOWN,
    }

    public enum SamplingAlgorithm {
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
