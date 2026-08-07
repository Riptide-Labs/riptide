/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow5;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.flows.utils.BufferUtils;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;

public class Netflow5UdpParser extends UdpParserBase implements DispatchableUdpParser {

    private final Netflow5FlowBuilder flowBuilder;

    public Netflow5UdpParser(final String name,
                             final BiConsumer<Source, List<Flow>> dispatcher,
                             final Identity identity,
                             final MetricRegistry metricRegistry) {
        super(Protocol.NETFLOW5, name, dispatcher, identity, metricRegistry);
        this.flowBuilder = new Netflow5FlowBuilder(name, metricRegistry);
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        return BufferUtils.uint16(buffer) == 0x0005;
    }

    @Override
    protected FlowPacket parse(final Session session,
                               final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(header, buffer);

        return new FlowPacket() {
            @Override
            public Stream<Flow> buildFlows(final Instant receivedAt) {
                return flowBuilder.buildFlows(receivedAt, packet);
            }

            @Override
            public long getObservationDomainId() {
                return packet.getObservationDomainId();
            }

            @Override
            public long getSequenceNumber() {
                return packet.getSequenceNumber();
            }

            @Override
            public int getSequenceIncrement() {
                return packet.getSequenceIncrement();
            }
        };
    }

    public Netflow5UdpParser withFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowBuilder.setFlowSamplingIntervalFallback(flowSamplingIntervalFallback);
        return this;
    }

    public Netflow5UdpParser withTrustHeaderSamplingInterval(final boolean trustHeaderSamplingInterval) {
        this.flowBuilder.setTrustHeaderSamplingInterval(trustHeaderSamplingInterval);
        return this;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress,
                                                           final InetSocketAddress localAddress) {
        return new Netflow9UdpParser.HostSessionKey(remoteAddress.getAddress(), localAddress);
    }
}
