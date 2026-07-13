/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.sflow.SflowFlowBuilder;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.uint32;

/**
 * An sFlow v5 datagram (sflow_version_5.txt §5.1). Identity is carried in the payload:
 * {@code agent_address} + {@code sub_agent_id}, not the UDP source. Sample types other
 * than flow samples — counter samples arrive interleaved on real agents — are skipped
 * by their XDR length without raising errors.
 */
public final class Datagram implements FlowPacket {

    public static final int VERSION = 5;

    /** Standard-enterprise sample formats (§5.3). */
    private static final int SAMPLE_FLOW = 1;
    private static final int SAMPLE_FLOW_EXPANDED = 3;

    public final InetAddress agentAddress;
    public final long subAgentId;
    public final long sequence;
    public final long uptime;

    public final List<FlowSample> samples;

    public Datagram(final ByteBuf buffer) throws InvalidPacketException {
        final long version = uint32(buffer);
        if (version != VERSION) {
            throw new InvalidPacketException(buffer, "Invalid version: %d", version);
        }

        this.agentAddress = ExtendedData.address(buffer);
        if (this.agentAddress == null) {
            throw new InvalidPacketException(buffer, "Invalid agent address");
        }
        if (buffer.readableBytes() < 16) {
            throw new InvalidPacketException(buffer, "Truncated datagram header");
        }
        this.subAgentId = uint32(buffer);
        this.sequence = uint32(buffer);
        this.uptime = uint32(buffer);

        final List<FlowSample> samples = new ArrayList<>();
        Xdr.walk(buffer, uint32(buffer), "sample", (format, sample) -> {
            switch (format) {
                case SAMPLE_FLOW -> samples.add(new FlowSample(sample, false));
                case SAMPLE_FLOW_EXPANDED -> samples.add(new FlowSample(sample, true));
                default -> {
                    // counter samples and anything else: skip by length
                }
            }
        });
        this.samples = samples;
    }

    @Override
    public Stream<Flow> buildFlows(final Instant receivedAt) {
        return this.samples.stream()
                .map(sample -> SflowFlowBuilder.buildFlow(receivedAt, this, sample));
    }

    @Override
    public ExporterIdentity identity(final InetAddress remoteAddress) {
        return new ExporterIdentity.Sflow(this.agentAddress, this.subAgentId);
    }

    @Override
    public long getObservationDomainId() {
        // informational only: sequence tracking is scoped by identity(), which this
        // class overrides with the full (agent address, sub-agent) payload identity
        return this.subAgentId;
    }

    @Override
    public long getSequenceNumber() {
        return this.sequence;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("agentAddress", this.agentAddress)
                .add("subAgentId", this.subAgentId)
                .add("sequence", this.sequence)
                .add("uptime", this.uptime)
                .add("samples", this.samples)
                .toString();
    }
}
