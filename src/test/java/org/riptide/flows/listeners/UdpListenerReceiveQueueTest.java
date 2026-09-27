/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receive queue gauge read through a real listener on a real kernel: with the read loop held
 * inside the parser, datagrams pile up in the socket, and the gauge must see them against the
 * buffer the kernel granted. Linux only, because only Linux has {@code /proc/net/udp}.
 */
class UdpListenerReceiveQueueTest {

    /** The kernel's accounting size of one 1,200-byte loopback datagram, with headroom: far below half a buffer. */
    private static final long ONE_DATAGRAM_ALLOCATION = 4_096;

    @Test
    @EnabledOnOs(OS.LINUX)
    void aBlockedReadLoopLeavesBytesQueuedWithinTheGrantedBuffer() throws Exception {
        final var registry = new MetricRegistry();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var listener = new UdpListener("queued", blockingParser(entered, release), registry)
                .withHost("127.0.0.1")
                .withPort(0);
        listener.start();
        try {
            final String description = listener.getDescription();
            final int port = Integer.parseInt(description.substring(description.lastIndexOf(':') + 1));
            try (var socket = new DatagramSocket()) {
                final var loopback = InetAddress.getLoopbackAddress();
                socket.send(new DatagramPacket(new byte[512], 512, loopback, port));
                assertThat(entered.await(10, TimeUnit.SECONDS)).as("read loop holds the first datagram").isTrue();
                for (int i = 0; i < 20; i++) {
                    socket.send(new DatagramPacket(new byte[512], 512, loopback, port));
                }
            }

            final var queue = (Long) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "queued", "receiveQueueBytes")).getValue();
            final var buffer = (Integer) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "queued", "receiveBufferBytes")).getValue();
            assertThat(queue).as("20 datagrams wait behind the blocked loop").isGreaterThan(0L);
            assertThat(queue).as("the queue never exceeds the granted buffer").isLessThanOrEqualTo(buffer.longValue());
        } finally {
            release.countDown();
            listener.stop();
        }
    }

    /**
     * The buffer gauge must be the kernel's own {@code sk_rcvbuf}, the value {@code rx_queue} is
     * compared with to decide a drop. On Linux the JDK halves {@code SO_RCVBUF} on read, to hide the
     * kernel doubling the request, so a gauge that reports the JDK's figure is half the real buffer
     * and the fill ratio reads 1.0 with the socket half full. Push a blocked listener until the kernel
     * drops: the queue then sits near the real buffer, above half of it. It can pass the buffer by
     * one datagram: the kernel refuses a datagram only when the queue is already over, so the last one
     * it admits may cross it (CI saw 2,098,944 against 2,097,152, one 1,792-byte allocation over).
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theBufferGaugeIsTheKernelsBufferNotTheJdksHalf() throws Exception {
        final var registry = new MetricRegistry();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var listener = new UdpListener("full", blockingParser(entered, release), registry)
                .withHost("127.0.0.1")
                .withPort(0);
        listener.start();
        try {
            final String description = listener.getDescription();
            final int port = Integer.parseInt(description.substring(description.lastIndexOf(':') + 1));
            final var drops = registry.getGauges().get(MetricRegistry.name("listeners", "full", "socketDrops"));
            try (var socket = new DatagramSocket()) {
                final var loopback = InetAddress.getLoopbackAddress();
                socket.send(new DatagramPacket(new byte[1200], 1200, loopback, port));
                assertThat(entered.await(10, TimeUnit.SECONDS)).as("read loop holds the first datagram").isTrue();
                // until the kernel has dropped some: the queue is then as full as the buffer allows
                for (int i = 0; i < 200_000 && ((Long) drops.getValue()) == 0L; i++) {
                    socket.send(new DatagramPacket(new byte[1200], 1200, loopback, port));
                }
            }
            assertThat((Long) drops.getValue()).as("the kernel dropped datagrams").isPositive();

            final long queue = (Long) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "full", "receiveQueueBytes")).getValue();
            final long buffer = ((Integer) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "full", "receiveBufferBytes")).getValue()).longValue();
            assertThat(queue).as("a full queue passes the kernel's buffer by at most one datagram")
                    .isLessThanOrEqualTo(buffer + ONE_DATAGRAM_ALLOCATION);
            assertThat(queue).as("a full queue is well past half the buffer").isGreaterThan(buffer / 2);
        } finally {
            release.countDown();
            listener.stop();
        }
    }

    private static UdpParser blockingParser(final CountDownLatch entered, final CountDownLatch release) {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) throws InterruptedException {
                entered.countDown();
                release.await(20, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "queued";
            }

            @Override
            public String getDescription() {
                return "queued";
            }

            @Override
            public Object dumpInternalState() {
                return null;
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        };
    }
}
