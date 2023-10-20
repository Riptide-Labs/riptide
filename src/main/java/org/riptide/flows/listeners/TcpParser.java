package org.riptide.flows.listeners;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.Parser;

public interface TcpParser extends Parser {
    interface Handler {
        void inactive();
        void active();

        Optional<CompletableFuture<?>> parse(final ByteBuf buffer) throws Exception;
    }

    Handler accept(final InetSocketAddress remoteAddress,
                   final InetSocketAddress localAddress);
}
