/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code UdpListener}'s event loop group is also the parser's {@code ScheduledExecutorService}:
 * {@code start()} hands it to {@code parser.start(...)}, and {@code UdpParserBase} schedules
 * {@code doHousekeeping} on it every 60s — once per sub-parser under {@code DispatchingUdpParser}.
 *
 * <p>The regression this pins: sizing the group to one loop on the reasoning that a single
 * {@code DatagramChannel} only occupies one. {@code scheduleAtFixedRate} calls {@code next()}, so
 * with the default group the sweep runs on a different loop from the socket; with one loop it runs
 * on the thread draining the socket, turning every sweep into a pause in packet reception that
 * surfaces as kernel loss on the {@code socketDrops} gauge rather than as an application error.
 *
 * <p>Sizing this group is issue #450 and needs the ingest path measured first.
 */
class UdpListenerSchedulerIsolationTest {

    @Test
    void scheduledParserWorkDoesNotShareTheThreadDrainingTheSocket() {
        final var listener = new UdpListener("isolation", schedulingParser(), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        listener.start();
        try {
            final var loopThreads = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith("udp-listener-nio-isolation-"))
                    .collect(Collectors.toSet());

            assertThat(loopThreads)
                    .as("the parser's scheduled housekeeping must not run on the socket's event loop")
                    .hasSizeGreaterThan(1);
        } finally {
            listener.stop();
        }
    }

    /** Stands in for {@code UdpParserBase}, which schedules housekeeping on the group it is given. */
    private static UdpParser schedulingParser() {
        return new UdpParser() {
            @Override
            public void start(final ScheduledExecutorService executorService) {
                executorService.scheduleAtFixedRate(() -> {
                }, 60_000, 60_000, TimeUnit.MILLISECONDS);
            }

            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "isolation";
            }

            @Override
            public String getDescription() {
                return "isolation";
            }

            @Override
            public Object dumpInternalState() {
                return null;
            }

            @Override
            public void stop() {
            }
        };
    }
}
