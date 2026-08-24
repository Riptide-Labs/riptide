/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint16;

public class IpfixUdpParser extends UdpParserBase implements DispatchableUdpParser {

    private final IpFixFlowBuilder flowBuilder;

    public IpfixUdpParser(final String name,
                          final BiConsumer<Source, List<Flow>> dispatcher,
                          final Identity identity,
                          final MetricRegistry metricRegistry,
                          @Qualifier("ipfixValueConversionService") final ValueConversionService conversionService) {
        super(Protocol.IPFIX, name, dispatcher, identity, metricRegistry);
        this.flowBuilder = new IpFixFlowBuilder(conversionService);
    }

    @Override
    protected FlowPacket parse(final Session session,
                               final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, slice(buffer, header.payloadLength()));
        // Same identity the option tap builds when it consumes a sampler options record, so a
        // lookup finds what that record deposited (UdpSessionManager keys on remote address plus
        // observation domain).
        final ExporterIdentity exporter =
                new ExporterIdentity.NetflowIpfix(session.getRemoteAddress(), header.observationDomainId);

        return new FlowPacket() {
            @Override
            public Stream<Flow> buildFlows(Instant receivedAt) {
                return flowBuilder.buildFlows(receivedAt, packet, exporter);
            }

            @Override
            public long getObservationDomainId() {
                return header.observationDomainId;
            }

            @Override
            public long getSequenceNumber() {
                return header.sequenceNumber;
            }

            @Override
            public int getSequenceIncrement() {
                // IPFIX sequence numbers count Data Records (RFC 7011 §3.1)
                return packet.dataRecordCount();
            }

            @Override
            public int undecodableSets() {
                return packet.undecodableSets;
            }
        };
    }

    @Override
    public boolean handles(final ByteBuf buffer) {
        return uint16(buffer) == Header.VERSION;
    }

    @Override
    protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
        return new SocketSessionKey(remoteAddress, localAddress);
    }

    /**
     * Keys the session on the full remote <em>socket</em> (address and port) plus the local socket:
     * an IPFIX UDP Transport Session is per source/destination tuple, so an exporter that moves
     * source port is a new session. Contrast {@code Netflow9UdpParser.HostSessionKey}.
     */
    public static final class SocketSessionKey implements UdpSessionManager.SessionKey {
        private final InetSocketAddress remoteAddress;
        private final InetSocketAddress localAddress;

        public SocketSessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof SocketSessionKey that)) return false;
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
            return String.format("%s:%s", this.remoteAddress.getHostString(), this.remoteAddress.getPort());
        }

        @Override
        public InetAddress getRemoteAddress() {
            return this.remoteAddress.getAddress();
        }

    }

    public IpfixUdpParser withFlowActiveTimeoutFallback(final Duration flowActiveTimeoutFallback) {
        this.flowBuilder.setFlowActiveTimeoutFallback(flowActiveTimeoutFallback);
        return this;
    }

    public IpfixUdpParser withFlowInactiveTimeoutFallback(final Duration flowInactiveTimeoutFallback) {
        this.flowBuilder.setFlowInactiveTimeoutFallback(flowInactiveTimeoutFallback);
        return this;
    }

    /** Rates learned from sampler options records; see {@link ExporterSamplingTable}. */
    public IpfixUdpParser withSamplingTable(final ExporterSamplingTable samplingTable) {
        this.flowBuilder.setSamplingTable(samplingTable);
        return this;
    }

    public IpfixUdpParser withFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowBuilder.setFlowSamplingIntervalFallback(flowSamplingIntervalFallback);
        return this;
    }
}
