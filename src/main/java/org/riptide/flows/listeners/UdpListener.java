/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.SocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class UdpListener implements Listener {
    private static final Logger LOG = LoggerFactory.getLogger(UdpListener.class);

    private final String name;
    private final UdpParser parser;

    private final Meter packetsReceived;

    private EventLoopGroup bossGroup;
    private ChannelFuture socketFuture;

    private String host = null;
    private int port = 50000;
    private int maxPacketSize = 8096;

    public UdpListener(final String name,
                       final UdpParser parser,
                       final MetricRegistry metrics) {
        this.name = Objects.requireNonNull(name);
        this.parser = Objects.requireNonNull(parser);

        this.packetsReceived = metrics.meter(MetricRegistry.name("listeners", name, "packetsReceived"));
    }

    @Override
    public void start() {
        // Netty defaults to 2 * num cores when the number of threads is set to 0
        this.bossGroup = new NioEventLoopGroup(0, new ThreadFactoryBuilder()
                .setNameFormat("udp-listener-nio-" + name + "-%d")
                .build());

        this.parser.start(this.bossGroup);

        final InetSocketAddress address = this.host != null
                ? SocketUtils.socketAddress(this.host, this.port)
                : new InetSocketAddress(this.port);

        this.socketFuture = new Bootstrap()
                .group(this.bossGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, Integer.MAX_VALUE)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(this.maxPacketSize))
                .handler(new DefaultChannelInitializer())
                .bind(address)
                .syncUninterruptibly();
    }

    @Override
    public void stop() {
        if (this.socketFuture != null) {
            LOG.info("Closing channel...");
            this.socketFuture.channel().close().syncUninterruptibly();
            if (this.socketFuture.channel().parent() != null) {
                this.socketFuture.channel().parent().close().syncUninterruptibly();
            }
        }

        this.parser.stop();

        LOG.info("Closing boss group...");
        if (this.bossGroup != null) {
            // switch to use even listener rather than sync to prevent shutdown deadlock hang
            this.bossGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Override
    public boolean isListening() {
        return this.socketFuture != null && this.socketFuture.channel().isActive();
    }

    public UdpListener withHost(String host) {
        this.host = host;
        return this;
    }

    public UdpListener withPort(int port) {
        this.port = port;
        return this;
    }

    public UdpListener withMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return String.format("UDP %s:%s", this.host != null ? this.host : "*", this.port);
    }

    private class DefaultChannelInitializer extends ChannelInitializer<DatagramChannel> {

        @Override
        protected void initChannel(final DatagramChannel ch) {
            // Accounting
            ch.pipeline().addFirst(new AccountingHandler());

            // Call the parser
            ch.pipeline().addLast(new SingleDatagramPacketParserHandler(UdpListener.this.parser));

            // Backstop for channel-level errors. Parse errors never reach this: the parser handler
            // logs them itself (with the sender address), so anything landing here is a pipeline
            // or I/O fault — label it as such, not as a bad packet.
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                    LOG.warn("Unexpected error in UDP listener pipeline: {}", cause.toString());
                    LOG.debug("", cause);
                }
            });
        }
    }

    private class AccountingHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            packetsReceived.mark();
            super.channelRead(ctx, msg);
        }
    }

    // Invokes parse of the provided parsers and also adds some error handling.
    // Package-visible for the buffer-lifecycle test (UdpListenerReleaseTest).
    static final class SingleDatagramPacketParserHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        final UdpParser parser;

        SingleDatagramPacketParserHandler(UdpParser parser) {
            this.parser = parser;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
            final InetSocketAddress sender = msg.sender();
            final ByteBuf content = ReferenceCountUtil.retain(msg.content());
            CompletableFuture<?> future;
            try {
                future = parser.parse(Instant.now(), content, sender, msg.recipient());
            } catch (final Throwable e) {
                // Parse errors surface synchronously (and pathological packets can raise Errors) —
                // normalize into the future so the release below is the one and only path. Before
                // this, the retained buffer leaked once per malformed packet (#273).
                future = CompletableFuture.failedFuture(e);
            }
            if (future == null) {
                // A dispatching parser returns null for packets no sub-parser handles.
                ReferenceCountUtil.release(content);
                return;
            }
            future.whenComplete((_, ex) -> {
                ReferenceCountUtil.release(content);
                if (ex != null) {
                    logInvalidPacket(sender, ex);
                }
            });
        }

        // Logged here rather than via fireExceptionCaught: only this handler knows the sender,
        // and with several exporters on one listener the address is what makes the log actionable.
        private static void logInvalidPacket(final InetSocketAddress sender, final Throwable cause) {
            // Async stages wrap failures in CompletionException; unwrap for a clean message.
            final Throwable root = cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null
                    ? cause.getCause() : cause;
            // InvalidPacketException messages carry a multi-KB hex dump after the first line — keep
            // the WARN to the summary line; the full dump and stack trace are available at DEBUG.
            final String message = String.valueOf(root.getMessage());
            final int newline = message.indexOf('\n');
            LOG.warn("Invalid packet from {}: {}", sender, newline > 0 ? message.substring(0, newline) : message);
            LOG.debug("Invalid packet from {}:", sender, root);
        }
    }

}
