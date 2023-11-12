package org.riptide.flows.parser.netflow5;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.flows.utils.BufferUtils;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.riptide.flows.utils.BufferUtils.slice;

public class Netflow5UdpParser extends UdpParserBase implements DispatchableUdpParser {

    private final Netflow5FlowBuilder flowBuilder = new Netflow5FlowBuilder();

    public Netflow5UdpParser(final String name,
                             final BiConsumer<Source, Flow> dispatcher,
                             final String location,
                             final MetricRegistry metricRegistry) {
        super(Protocol.NETFLOW5, name, dispatcher, location, metricRegistry);
    }

    public Netflow5FlowBuilder getFlowBulder() {
        return this.flowBuilder;
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        return BufferUtils.uint16(buffer) == 0x0005;
    }

    @Override
    protected RecordProvider parse(final Session session,
                                   final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(header, buffer);

        return packet;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress,
                                                           final InetSocketAddress localAddress) {
        return new Netflow9UdpParser.SessionKey(remoteAddress.getAddress(), localAddress);
    }
}
