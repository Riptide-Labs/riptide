package org.riptide.flows.listeners;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.Parser;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

public interface UdpParser extends Parser {
    CompletableFuture<?> parse(ByteBuf buffer,
                               InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress) throws Exception;
}
