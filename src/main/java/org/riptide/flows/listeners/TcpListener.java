/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.SocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class TcpListener implements Listener {
    private static final Logger LOG = LoggerFactory.getLogger(TcpListener.class);
    private final String name;
    private final TcpParser parser;
    private final Meter packetsReceived;
    private String host = null;
    private int port = 50000;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture socketFuture;
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public TcpListener(final String name,
                       final TcpParser parser,
                       final MetricRegistry metrics) {
        this.name = Objects.requireNonNull(name);
        this.parser = Objects.requireNonNull(parser);
        this.packetsReceived = metrics.meter(MetricRegistry.name("listeners", name, "packetsReceived"));
    }

    @Override
    public void start() {
        // One thread: ServerBootstrap registers a single channel, so only one event loop is ever
        // selected for accept. Netty's default (0 = 2 * num cores) would build the rest as loops
        // nothing can reach — they hold a selector each and never run. A second bind() on this
        // listener would make the count matter again.
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, new ThreadFactoryBuilder()
                .setNameFormat("tcp-listener-nio-boss-" + name + "-%d")
                .build(), NioIoHandler.newFactory());
        // Netty defaults to 2 * num cores when the number of threads is set to 0; here that is
        // real capacity, since each accepted connection is assigned a loop round-robin.
        this.workerGroup = new MultiThreadIoEventLoopGroup(0, new ThreadFactoryBuilder()
                .setNameFormat("tcp-listener-nio-worker-" + name + "-%d")
                .build(), NioIoHandler.newFactory());

        this.parser.start(this.bossGroup);

        final InetSocketAddress address = this.host != null
                ? SocketUtils.socketAddress(this.host, this.port)
                : new InetSocketAddress(this.port);

        this.socketFuture = new ServerBootstrap()
                .group(this.bossGroup, this.workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        final TcpParser.Handler session = TcpListener.this.parser.accept(ch.remoteAddress(), ch.localAddress());
                        ch.pipeline()
                                .addFirst(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        packetsReceived.mark();
                                        super.channelRead(ctx, msg);
                                    }
                                })
                                .addLast(new ByteToMessageDecoder() {
                                    @Override
                                    protected void decode(final ChannelHandlerContext ctx,
                                                          final ByteBuf in,
                                                          final List<Object> out) throws Exception {
                                        session.parse(Instant.now(), in).ifPresent(out::add);
                                    }

                                    @Override
                                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                        super.channelActive(ctx);
                                        session.active();
                                    }

                                    @Override
                                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                        super.channelInactive(ctx);
                                        session.inactive();
                                    }
                                })
                                .addLast(new SimpleChannelInboundHandler<CompletableFuture<?>>() {
                                    // handle() is used for its side effect — pushing a parse
                                    // failure back into the pipeline — so the stage it returns has
                                    // no consumer by design.
                                    @SuppressWarnings("FutureReturnValueIgnored")
                                    @Override
                                    protected void channelRead0(final ChannelHandlerContext ctx,
                                                                final CompletableFuture<?> future) throws Exception {
                                        future.handle((result, ex) -> {
                                            if (ex != null) {
                                                ctx.fireExceptionCaught(ex);
                                            }
                                            return result;
                                        });
                                    }
                                })
                                .addLast(new ChannelInboundHandlerAdapter() {
                                    // ctx.close() is fire-and-forget: the connection is already
                                    // being torn down for a bad packet and there is nothing to do
                                    // with the close future.
                                    @SuppressWarnings("FutureReturnValueIgnored")
                                    @Override
                                    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
                                        LOG.warn("Invalid packet: {}", cause.getMessage());
                                        LOG.debug("", cause);

                                        session.inactive();

                                        ctx.close();
                                    }
                                });
                    }

                    @Override
                    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                        TcpListener.this.channels.add(ctx.channel());
                        super.channelActive(ctx);
                    }

                    @Override
                    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
                        TcpListener.this.channels.remove(ctx.channel());
                        super.channelInactive(ctx);
                    }
                })
                .bind(address)
                .syncUninterruptibly();
    }

    @Override
    public void stop() {
        LOG.info("Disconnecting clients...");
        this.channels.close().awaitUninterruptibly();

        if (this.socketFuture != null) {
            LOG.info("Closing channel...");
            this.socketFuture.channel().close().syncUninterruptibly();
            if (this.socketFuture.channel().parent() != null) {
                this.socketFuture.channel().parent().close().syncUninterruptibly();
            }
        }

        LOG.info("Stopping parser...");
        if (this.parser != null) {
            this.parser.stop();
        }

        LOG.info("Closing worker group...");
        // switch to use even listener rather than sync to prevent shutdown deadlock hang
        this.workerGroup.shutdownGracefully().syncUninterruptibly();

        LOG.info("Closing boss group...");
        this.bossGroup.shutdownGracefully().syncUninterruptibly();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getDescription() {
        return String.format("TCP %s:%s",  this.host != null ? this.host : "*", this.port);
    }

    @Override
    public boolean isListening() {
        return this.socketFuture != null && this.socketFuture.channel().isActive();
    }

    public TcpListener withHost(final String host) {
        this.host = host;
        return this;
    }

    public TcpListener withPort(final int port) {
        this.port = port;
        return this;
    }
}
