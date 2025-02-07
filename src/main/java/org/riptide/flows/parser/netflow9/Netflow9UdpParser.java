package org.riptide.flows.parser.netflow9;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint16;

public class Netflow9UdpParser extends UdpParserBase implements DispatchableUdpParser {

    private final Netflow9FlowBuilder flowBuilder;

    public Netflow9UdpParser(final String name,
                             final BiConsumer<Source, Flow> dispatcher,
                             final String location,
                             final MetricRegistry metricRegistry,
                             final ValueConversionService valueConversionService) {
        super(Protocol.NETFLOW9, name, dispatcher, location, metricRegistry);
        this.flowBuilder = new Netflow9FlowBuilder(valueConversionService);
    }

    @Override
    protected FlowPacket parse(final Session session,
                               final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, buffer);

        return new FlowPacket() {
            @Override
            public Stream<Flow> buildFlows(final Instant receivedAt) {
                return flowBuilder.buildFlows(receivedAt, packet);
            }

            @Override
            public long getObservationDomainId() {
                return header.sourceId;
            }

            @Override
            public long getSequenceNumber() {
                return header.sequenceNumber;
            }
        };
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        return uint16(buffer) == Header.VERSION;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
        return new SessionKey(remoteAddress.getAddress(), localAddress);
    }

    public static class SessionKey implements UdpSessionManager.SessionKey {
        private final InetAddress remoteAddress;
        private final InetSocketAddress localAddress;

        public SessionKey(final InetAddress remoteAddress, final InetSocketAddress localAddress) {
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final SessionKey that = (SessionKey) o;
            return Objects.equals(this.localAddress, that.localAddress)
                    && Objects.equals(this.remoteAddress, that.remoteAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.localAddress, this.remoteAddress);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("remoteAddress", remoteAddress)
                    .add("localAddress", localAddress)
                    .toString();
        }

        @Override
        public String getDescription() {
            return this.remoteAddress.getHostAddress();
        }

        @Override
        public InetAddress getRemoteAddress() {
            return this.remoteAddress;
        }
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
}
