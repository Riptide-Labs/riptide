/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The datagram buffer's lifecycle across every parse outcome. The regression (#273): parse errors
 * throw synchronously, so the release attached to the returned future never ran and the retained
 * buffer leaked — one per malformed packet, surfacing as Netty {@code LEAK} errors in production.
 */
class UdpListenerReleaseTest {

    private static final InetSocketAddress SENDER = new InetSocketAddress("127.0.0.1", 40000);
    private static final InetSocketAddress RECIPIENT = new InetSocketAddress("127.0.0.1", 4739);

    @Test
    void releasesBufferWhenParseThrowsSynchronously() {
        assertFullyReleased(parser((receivedAt, buffer, remote, local) -> {
            throw new IllegalArgumentException("Invalid set ID: 0");
        }));
    }

    @Test
    void releasesBufferWhenParseFailsAsynchronously() {
        assertFullyReleased(parser((receivedAt, buffer, remote, local) ->
                CompletableFuture.failedFuture(new IllegalStateException("dispatch failed"))));
    }

    @Test
    void releasesBufferOnSuccess() {
        assertFullyReleased(parser((receivedAt, buffer, remote, local) ->
                CompletableFuture.completedFuture(null)));
    }

    @Test
    void releasesBufferWhenParseThrowsAnError() {
        assertFullyReleased(parser((receivedAt, buffer, remote, local) -> {
            throw new StackOverflowError();
        }));
    }

    @Test
    void releasesBufferWhenNoParserHandlesThePacket() {
        // A dispatching parser returns null for packets no sub-parser handles.
        assertFullyReleased(parser((receivedAt, buffer, remote, local) -> null));
    }

    private static void assertFullyReleased(final UdpParser parser) {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new UdpListener.SingleDatagramPacketParserHandler(parser));
        final ByteBuf content = Unpooled.buffer().writeBytes(new byte[] {0, 0, (byte) 0x9f, 0x75});

        channel.writeInbound(new DatagramPacket(content, RECIPIENT, SENDER));

        // The handler must not leak the parse failure into the pipeline (it logs with the sender
        // itself), and the buffer must be fully released: the handler's retain undone plus
        // SimpleChannelInboundHandler's auto-release of the original reference.
        channel.checkException();
        assertThat(content.refCnt()).isZero();
        channel.finishAndReleaseAll();
    }

    @FunctionalInterface
    private interface ParseCall {
        CompletableFuture<?> parse(Instant receivedAt, ByteBuf buffer,
                                   InetSocketAddress remote, InetSocketAddress local) throws Exception;
    }

    private static UdpParser parser(final ParseCall call) {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) throws Exception {
                return call.parse(receivedAt, buffer, remoteAddress, localAddress);
            }

            @Override
            public String getName() {
                return "stub";
            }

            @Override
            public String getDescription() {
                return "stub";
            }

            @Override
            public Object dumpInternalState() {
                return null;
            }

            @Override
            public void start(final ScheduledExecutorService executorService) {
            }

            @Override
            public void stop() {
            }
        };
    }
}
