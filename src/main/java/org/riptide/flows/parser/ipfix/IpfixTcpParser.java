package org.riptide.flows.parser.ipfix;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Sets;
import io.netty.buffer.ByteBuf;
import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.Flow;
import org.riptide.flows.listeners.TcpParser;
import org.riptide.flows.parser.ParserBase;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.flows.parser.state.ParserState;
import org.riptide.pipeline.WithSource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;

public class IpfixTcpParser extends ParserBase implements TcpParser {

    private final IpFixFlowBuilder flowBuilder = new IpFixFlowBuilder();

    private final Set<TcpSession> sessions = Sets.newConcurrentHashSet();

    public IpfixTcpParser(final String name,
                          final Consumer<WithSource<Flow>> dispatcher,
//                          final EventForwarder eventForwarder,
//                          final Identity identity,
                          final String location,
                          final DnsResolver dnsResolver,
                          final MetricRegistry metricRegistry) {
        super(Protocol.IPFIX, name, dispatcher, /*eventForwarder, identity,*/ location, dnsResolver, metricRegistry);
    }

    @Override
    public IpFixFlowBuilder getFlowBulder() {
        return this.flowBuilder;
    }

    @Override
    public Handler accept(final InetSocketAddress remoteAddress,
                          final InetSocketAddress localAddress) {
        final TcpSession session = new TcpSession(remoteAddress.getAddress(), this::sequenceNumberTracker);

        return new Handler() {
            @Override
            public Optional<CompletableFuture<?>> parse(final ByteBuf buffer) throws Exception {
                buffer.markReaderIndex();

                final Header header;
                if (buffer.isReadable(Header.SIZE)) {
                    header = new Header(slice(buffer, Header.SIZE));
                } else {
                    buffer.resetReaderIndex();
                    return Optional.empty();
                }

                final Packet packet;
                if (buffer.isReadable(header.payloadLength())) {
                    packet = new Packet(session, header, slice(buffer, header.payloadLength()));
                } else {
                    buffer.resetReaderIndex();
                    return Optional.empty();
                }

                detectClockSkew(header.exportTime * 1000L, session.getRemoteAddress());

                return Optional.of(IpfixTcpParser.this.transmit(packet, session, remoteAddress));
            }

            @Override
            public void active() {
                sessions.add(session);
            }

            @Override
            public void inactive() {
                sessions.remove(session);
            }
        };
    }

    public Duration getFlowActiveTimeoutFallback() {
        return this.flowBuilder.getFlowActiveTimeoutFallback();
    }

    public void setFlowActiveTimeoutFallback(final Duration flowActiveTimeoutFallback) {
        this.flowBuilder.setFlowActiveTimeoutFallback(flowActiveTimeoutFallback);
    }

    public Duration getFlowInactiveTimeoutFallback() {
        return this.flowBuilder.getFlowInactiveTimeoutFallback();
    }

    public void setFlowInactiveTimeoutFallback(final Duration flowInactiveTimeoutFallback) {
        this.flowBuilder.setFlowInactiveTimeoutFallback(flowInactiveTimeoutFallback);
    }

    public Long getFlowSamplingIntervalFallback() {
        return this.flowBuilder.getFlowSamplingIntervalFallback();
    }

    public void setFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowBuilder.setFlowSamplingIntervalFallback(flowSamplingIntervalFallback);
    }

    @Override
    public Object dumpInternalState() {
        final ParserState.Builder parser = ParserState.builder();

        this.sessions.stream()
                     .flatMap(TcpSession::dumpInternalState)
                     .forEach(parser::withExporter);

        return parser.build();
    }
}
