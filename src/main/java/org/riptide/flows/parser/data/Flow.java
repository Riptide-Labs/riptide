/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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

    /**
     * Which rung of the resolution ladder supplied {@link #getSamplingInterval()}. A stored
     * interval of {@code 1.0} is otherwise ambiguous between an exporter stating it does not
     * sample — an answer, which outranks a configured fallback — and nothing being known at all.
     *
     * <p>Describes the interval only. {@link #getSamplingAlgorithm()} resolves through its own
     * ladder and may be {@code Unassigned} on a flow whose interval has a known provenance.
     */
    SamplingProvenance getSamplingProvenance();

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

    /**
     * Where a flow's sampling interval came from — one rung of the resolution ladder.
     *
     * <p>Each constant carries the token written to the {@code samplingProvenance} column and used
     * as the parser metric's leaf name. The token is the stable identifier: renaming a constant
     * must not rewrite what stored rows mean, and the metric and the column must not drift apart.
     */
    enum SamplingProvenance {
        /** Carried on the flow record itself (v9/IPFIX fields 34/49/50), or on an sFlow sample. */
        Record("record"),

        /** Learned from the exporter's sampler options table (NetFlow v9). */
        Options("options"),

        /** Read from the NetFlow v5 packet header. */
        Header("header"),

        /**
         * Computed by riptide from an IPFIX selector algorithm and its ranges. Distinct from
         * {@link #Record} deliberately: this is riptide's arithmetic on exporter-supplied inputs,
         * not a rate the exporter stated, so it carries less authority than one that was.
         */
        Derived("derived"),

        /** The receiver's {@code flow-sampling-interval-fallback}. */
        Fallback("fallback"),

        /**
         * Nothing stated a rate anywhere and {@code 1.0} was recorded in the absence of one. Not
         * the same claim as an exporter reporting a rate of 1: that exporter has answered that it
         * does not sample, and its answer is attributed to the rung that carried it.
         */
        Assumed("assumed");

        private final String token;

        SamplingProvenance(final String token) {
            this.token = token;
        }

        /** The stored/metered identifier for this rung. */
        public String token() {
            return this.token;
        }
    }
}
