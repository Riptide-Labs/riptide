package org.riptide.flows.parser.ipfix;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Sets;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.TcpParser;
import org.riptide.flows.parser.ParserBase;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.FlowPacket;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.flows.parser.state.ParserState;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;

public class IpfixTcpParser extends ParserBase implements TcpParser {

    private final IpFixFlowBuilder flowBuilder;

    private final Set<TcpSession> sessions = Sets.newConcurrentHashSet();

    public IpfixTcpParser(final String name,
                          final BiConsumer<Source, Flow> dispatcher,
                          final String location,
                          final MetricRegistry metricRegistry,
                          final ValueConversionService conversionService) {
        super(Protocol.IPFIX, name, dispatcher, location, metricRegistry);
        this.flowBuilder = new IpFixFlowBuilder(conversionService);
    }

    @Override
    public Handler accept(final InetSocketAddress remoteAddress,
                          final InetSocketAddress localAddress) {
        final TcpSession session = new TcpSession(remoteAddress.getAddress(), this::sequenceNumberTracker);

        return new Handler() {
            @Override
            public Optional<CompletableFuture<?>> parse(final Instant receivedAt, final ByteBuf buffer) throws Exception {
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

                final var flow = new FlowPacket() {
                    @Override
                    public Stream<Flow> buildFlows(Instant receivedAt) {
                        return flowBuilder.buildFlows(receivedAt, packet);
                    }

                    @Override
                    public long getObservationDomainId() {
                        return header.observationDomainId;
                    }

                    @Override
                    public long getSequenceNumber() {
                        return header.sequenceNumber;
                    }
                };

                return Optional.of(IpfixTcpParser.this.transmit(receivedAt, flow, session, remoteAddress));
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
