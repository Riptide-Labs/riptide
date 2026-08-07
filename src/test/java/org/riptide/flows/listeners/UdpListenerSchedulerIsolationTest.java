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
 * A {@code UdpListener}'s event loop group must drive its channel and nothing else.
 *
 * <p>History, because this assertion inverted and that reads like a weakening. It used to require
 * the group to start MORE than one thread: {@code start()} handed the group to
 * {@code parser.start(...)}, {@code UdpParserBase} scheduled a 60s sweep on it, and
 * {@code scheduleAtFixedRate} dispatches by round-robin — so a one-loop group would have put every
 * sweep on the thread draining the socket, turning it into a pause in packet reception that
 * surfaces as kernel loss on {@code socketDrops} rather than as an application error. That
 * regression was caught in review once already.
 *
 * <p>The parser owns its scheduler now (#457), so the group is sized to its one channel.
 *
 * <p>Note what this test does and does not prove after that inversion. It proves the SIZING: a
 * second thread here means something other than the channel took work on this group. It does NOT
 * prove the decoupling, because with one loop {@code next()} returns that same loop — a re-coupled
 * sweep would share the socket's thread and the count would still be 1. Verified: reverting the
 * parser to schedule on the passed executor leaves this test green. The decoupling guard is
 * {@code UdpParserHousekeepingTest}, which fails loudly on that revert. Both are needed; neither
 * covers the other.
 */
class UdpListenerSchedulerIsolationTest {

    @Test
    void theListenerGroupDrivesItsChannelAndNothingElse() {
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
                    .as("one channel needs one loop; a second means something else took work here")
                    .hasSize(1);
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
