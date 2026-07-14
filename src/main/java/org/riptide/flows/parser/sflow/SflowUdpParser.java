/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.flows.parser.sflow.proto.Datagram;
import org.riptide.flows.utils.BufferUtils;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

/**
 * sFlow v5 (sflow.org spec; not RFC 3176 v4). Stateless on the wire — no templates —
 * but datagram sequence numbers are still tracked, scoped by the full payload identity
 * ({@code agent_address} + {@code sub_agent_id}) via
 * {@link org.riptide.flows.parser.session.Session#verifySequenceNumber}.
 */
public class SflowUdpParser extends UdpParserBase implements DispatchableUdpParser {

    public SflowUdpParser(final String name,
                          final BiConsumer<Source, Flow> dispatcher,
                          final String location,
                          final MetricRegistry metricRegistry) {
        super(Protocol.SFLOW, name, dispatcher, location, metricRegistry);
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        // XDR uint32 version 5: [00 00 00 05] — NetFlow/IPFIX carry uint16 versions,
        // so their first two bytes are never zero
        return buffer.readableBytes() >= 4 && BufferUtils.uint32(buffer) == Datagram.VERSION;
    }

    @Override
    protected FlowPacket parse(final Session session,
                               final ByteBuf buffer) throws Exception {
        return new Datagram(buffer);
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress,
                                                           final InetSocketAddress localAddress) {
        return new Netflow9UdpParser.SessionKey(remoteAddress.getAddress(), localAddress);
    }
}
