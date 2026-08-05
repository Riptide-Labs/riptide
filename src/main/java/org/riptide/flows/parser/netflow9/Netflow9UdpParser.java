/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow9;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.UdpParserBase;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.FlowPacket;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;

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

public class Netflow9UdpParser extends UdpParserBase implements DispatchableUdpParser {

    private final Netflow9FlowBuilder flowBuilder;

    public Netflow9UdpParser(final String name,
                             final BiConsumer<Source, List<Flow>> dispatcher,
                             final Identity identity,
                             final MetricRegistry metricRegistry,
                             final ValueConversionService valueConversionService) {
        super(Protocol.NETFLOW9, name, dispatcher, identity, metricRegistry);
        this.flowBuilder = new Netflow9FlowBuilder(valueConversionService);
    }

    @Override
    protected FlowPacket parse(final Session session,
                               final ByteBuf buffer) throws Exception {
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, buffer);
        // The same identity the option tap keys sampler rates by, so a rate learned from this
        // exporter's options table is found again here.
        final ExporterIdentity exporter =
                new ExporterIdentity.NetflowIpfix(session.getRemoteAddress(), header.sourceId);

        return new FlowPacket() {
            @Override
            public Stream<Flow> buildFlows(final Instant receivedAt) {
                return flowBuilder.buildFlows(receivedAt, packet, exporter);
            }

            @Override
            public long getObservationDomainId() {
                return header.sourceId;
            }

            @Override
            public long getSequenceNumber() {
                return header.sequenceNumber;
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
        return new HostSessionKey(remoteAddress.getAddress(), localAddress);
    }

    /**
     * Keys the session on the remote <em>host address</em> plus the local socket, deliberately
     * ignoring the remote port: NetFlow v9 exporters may hop source ports between packets, and the
     * templates they announce must survive that. NetFlow v5 and sFlow reuse this key for the same
     * reason. Contrast {@code IpfixUdpParser.SocketSessionKey}.
     */
    public static final class HostSessionKey implements UdpSessionManager.SessionKey {
        private final InetAddress remoteAddress;
        private final InetSocketAddress localAddress;

        public HostSessionKey(final InetAddress remoteAddress, final InetSocketAddress localAddress) {
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof HostSessionKey that)) return false;
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

    public Netflow9UdpParser withFlowActiveTimeoutFallback(final Duration flowActiveTimeoutFallback) {
        this.flowBuilder.setFlowActiveTimeoutFallback(flowActiveTimeoutFallback);
        return this;
    }

    public Netflow9UdpParser withFlowInactiveTimeoutFallback(final Duration flowInactiveTimeoutFallback) {
        this.flowBuilder.setFlowInactiveTimeoutFallback(flowInactiveTimeoutFallback);
        return this;
    }

    public Netflow9UdpParser withFlowSamplingIntervalFallback(final Long flowSamplingIntervalFallback) {
        this.flowBuilder.setFlowSamplingIntervalFallback(flowSamplingIntervalFallback);
        return this;
    }

    /** Rates learned from sampler options records; see {@link ExporterSamplingTable}. */
    public Netflow9UdpParser withSamplingTable(final ExporterSamplingTable samplingTable) {
        this.flowBuilder.setSamplingTable(samplingTable);
        return this;
    }
}
