package org.riptide.flows.parser.ipfix;

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
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.WithSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint16;

public class IpfixUdpParser extends UdpParserBase implements UdpParser, Dispatchable {

    private final IpFixFlowBuilder flowBuilder = new IpFixFlowBuilder();

    public IpfixUdpParser(final String name,
                          final Consumer<WithSource<Flow>> dispatcher,
//                          final EventForwarder eventForwarder,
//                          final Identity identity,
                          final String location,
                          final DnsResolver dnsResolver,
                          final MetricRegistry metricRegistry) {
        super(Protocol.IPFIX, name, dispatcher, /*eventForwarder, identity,*/ location, dnsResolver, metricRegistry);
    }

    public IpFixFlowBuilder getFlowBulder() {
        return this.flowBuilder;
    }

    @Override
    protected RecordProvider parse(final Session session,
                                   final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, slice(buffer, header.payloadLength()));

        detectClockSkew(header.exportTime * 1000L, session.getRemoteAddress());

        return packet;
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        return uint16(buffer) == Header.VERSION;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
        return new SessionKey(remoteAddress, localAddress);
    }

    public static class SessionKey implements UdpSessionManager.SessionKey {
        private final InetSocketAddress remoteAddress;
        private final InetSocketAddress localAddress;

        public SessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final SessionKey that = (SessionKey) o;
            return Objects.equal(this.localAddress, that.localAddress) &&
                    Objects.equal(this.remoteAddress, that.remoteAddress);
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
            return String.format("%s:%s", this.remoteAddress.getHostString(), this.remoteAddress.getPort());
        }

        @Override
        public InetAddress getRemoteAddress() {
            return this.remoteAddress.getAddress();
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
