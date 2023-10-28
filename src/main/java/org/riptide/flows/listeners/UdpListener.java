package org.riptide.flows.listeners;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
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
import java.util.Objects;

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

    public void start() {
        // Netty defaults to 2 * num cores when the number of threads is set to 0
        this.bossGroup = new NioEventLoopGroup(0, new ThreadFactoryBuilder()
                .setNameFormat("telemetryd-nio-" + name + "-%d")
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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
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

            // Add error handling
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                    LOG.warn("Invalid packet: {}", cause.getMessage());
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

    // Invokes parse of the provided parsers and also adds some error handling
    private static final class SingleDatagramPacketParserHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        final UdpParser parser;

        private SingleDatagramPacketParserHandler(UdpParser parser) {
            this.parser = parser;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
            parser.parse(
                    ReferenceCountUtil.retain(msg.content()),
                    msg.sender(), msg.recipient()
            ).handle((result, ex) -> {
                ReferenceCountUtil.release(msg.content());
                if (ex != null) {
                    ctx.fireExceptionCaught(ex);
                }
                return result;
            });
        }
    }

}
