package org.riptide.flows.parser.netflow9;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.netty.buffer.ByteBuf;
import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.listeners.Dispatchable;
import org.riptide.flows.listeners.UdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.WithSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint16;

public class Netflow9UdpParser extends UdpParserBase implements UdpParser, Dispatchable {

    private final Netflow9FlowBuilder flowBuilder = new Netflow9FlowBuilder();

    public Netflow9UdpParser(final String name,
                             final Consumer<WithSource<Flow>> dispatcher,
//                             final EventForwarder eventForwarder,
//                             final Identity identity,
                             final String location,
                             final DnsResolver dnsResolver,
                             final MetricRegistry metricRegistry) {
        super(Protocol.NETFLOW9, name, dispatcher, /*eventForwarder, identity,*/ location, dnsResolver, metricRegistry);
    }

    public Netflow9FlowBuilder getFlowBulder() {
        return this.flowBuilder;
    }

    @Override
    protected RecordProvider parse(final Session session,
                                   final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, buffer);

        detectClockSkew(header.unixSecs * 1000L, session.getRemoteAddress());

        return packet;
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
            return Objects.equal(this.localAddress, that.localAddress)
                    && Objects.equal(this.remoteAddress, that.remoteAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.localAddress, this.remoteAddress);
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
