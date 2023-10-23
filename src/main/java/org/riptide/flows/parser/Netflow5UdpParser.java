package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.Flow;
import org.riptide.flows.listeners.Dispatchable;
import org.riptide.flows.listeners.UdpParser;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.flows.parser.transport.Netflow5FlowBuilder;
import org.riptide.pipeline.WithSource;
import org.riptide.flows.utils.BufferUtils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;

public class Netflow5UdpParser extends UdpParserBase implements UdpParser, Dispatchable {

    private final Netflow5FlowBuilder flowBuilder = new Netflow5FlowBuilder();

    public Netflow5UdpParser(final String name,
                             final Consumer<WithSource<Flow>> dispatcher,
//                             final EventForwarder eventForwarder,
//                             final Identity identity,
                             final String location,
                             final DnsResolver dnsResolver,
                             final MetricRegistry metricRegistry) {
        super(Protocol.NETFLOW5, name, dispatcher, /*eventForwarder, identity,*/ location, dnsResolver, metricRegistry);
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

        detectClockSkew(Duration.ofSeconds(header.unixSecs, header.unixNSecs).toMillis(), session.getRemoteAddress());

        return packet;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress,
                                                           final InetSocketAddress localAddress) {
        return new Netflow9UdpParser.SessionKey(remoteAddress.getAddress(), localAddress);
    }
}
