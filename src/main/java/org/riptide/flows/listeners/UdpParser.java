package org.riptide.flows.listeners;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.Parser;

public interface UdpParser extends Parser {
    CompletableFuture<?> parse(final ByteBuf buffer,
                               final InetSocketAddress remoteAddress,
                               final InetSocketAddress localAddress) throws Exception;
}
