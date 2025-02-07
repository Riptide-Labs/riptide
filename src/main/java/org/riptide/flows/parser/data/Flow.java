package org.riptide.flows.parser.data;

import java.time.Instant;

public interface Flow {
    Instant getReceivedAt();

    Instant getTimestamp();

    FlowProtocol getFlowProtocol();
    int getFlowRecords();
    long getFlowSeqNum();

    Instant getFirstSwitched();
    Instant getLastSwitched();
    default Instant getDeltaSwitched() {
        return this.getFirstSwitched();
    }

    int getInputSnmp();
    int getOutputSnmp();

    long getSrcAs();
    java.net.InetAddress getSrcAddr();
    int getSrcMaskLen();
    int getSrcPort();

    long getDstAs();
    java.net.InetAddress getDstAddr();
    int getDstMaskLen();
    int getDstPort();

    java.net.InetAddress getNextHop();

    long getBytes();
    long getPackets();

    Direction getDirection();

    int getEngineId();
    int getEngineType();

    int getVlan();
    int getIpProtocolVersion();
    int getProtocol();
    int getTcpFlags();
    int getTos();

    SamplingAlgorithm getSamplingAlgorithm();
    double getSamplingInterval();

    enum Locality {
        PUBLIC, PRIVATE
    }

    enum FlowProtocol {
        NetflowV5, NetflowV9, IPFIX, SFLOW,
    }

    enum Direction {
        INGRESS, EGRESS, UNKNOWN,
    }

    enum SamplingAlgorithm {
        Unassigned, SystematicCountBasedSampling, SystematicTimeBasedSampling, RandomNOutOfNSampling, UniformProbabilisticSampling, PropertyMatchFiltering, HashBasedFiltering, FlowStateDependentIntermediateFlowSelectionProcess;
    }
}
