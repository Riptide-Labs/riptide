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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A listener must release everything it acquired, whether or not it finished starting and whether
 * or not an individual release fails.
 *
 * <p>Two regressions are pinned here. The first: {@code Daemon} constructs listeners in one
 * lifecycle phase and starts them in another ({@code ApplicationRunner.run()}), so a bean failing
 * during context refresh leaves constructed-but-unstarted listeners that are then torn down —
 * {@code TcpListener.stop()} used to NPE on its unassigned event loop groups (#452), burying the
 * real startup error under an unrelated stack.
 *
 * <p>The second: every teardown step calls {@code syncUninterruptibly()}, which sneaky-throws the
 * future's cause, so straight-line teardown skipped every step below the first failure and stranded
 * the event loop threads behind it.
 */
class ListenerTeardownTest {

    @Test
    void tcpListenerStopsCleanlyWhenStartNeverRan() {
        final var listener = new TcpListener("unstarted", tcpParser(null), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        assertThatCode(listener::stop)
                .as("a constructed-but-unstarted listener owns nothing; tearing it down is a normal path")
                .doesNotThrowAnyException();
    }

    @Test
    void udpListenerStopsCleanlyWhenStartNeverRan() {
        final var listener = new UdpListener("unstarted", udpParser(null), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        assertThatCode(listener::stop).doesNotThrowAnyException();
    }

    @Test
    void aFailingStepDoesNotStrandTheEventLoops() {
        final var failure = new IllegalStateException("parser teardown blew up");
        final var listener = new TcpListener("stranded", tcpParser(failure), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        listener.start();
        assertThat(liveThreadNames("tcp-listener-nio-boss-stranded-"))
                .as("precondition: the listener owns an event loop before we stop it")
                .isNotEmpty();

        assertThatThrownBy(listener::stop)
                .as("the failure must still reach the caller, not be swallowed")
                .isSameAs(failure);

        awaitNoThreads("tcp-listener-nio-boss-stranded-",
                "the parser's failure must not prevent the event loop groups from shutting down");
        awaitNoThreads("tcp-listener-nio-worker-stranded-",
                "the parser's failure must not prevent the event loop groups from shutting down");
    }

    @Test
    void multipleFailuresAreReportedTogether() {
        final var parserFailure = new IllegalStateException("parser teardown blew up");
        final var metricsFailure = new IllegalStateException("gauge removal blew up");

        // Fails on the gauge removal (first step) as well as on the parser, so two steps fail in
        // one teardown — unreachable before this change, because the first failure ended it.
        // Armed only after start(), since registerSocketDrops() also calls remove() to rebind the
        // gauge to the live socket.
        final var armed = new java.util.concurrent.atomic.AtomicBoolean();
        final var metrics = new MetricRegistry() {
            @Override
            public boolean remove(final String name) {
                if (armed.get()) {
                    throw metricsFailure;
                }
                return super.remove(name);
            }
        };
        final var listener = new UdpListener("multi", udpParser(parserFailure), metrics)
                .withHost("127.0.0.1")
                .withPort(0);

        listener.start();
        armed.set(true);

        assertThatThrownBy(listener::stop)
                .as("the first failure is reported as-is, so Netty's sneaky-thrown causes survive")
                .isSameAs(metricsFailure)
                .satisfies(t -> assertThat(t.getSuppressed())
                        .as("later failures are attached rather than discarded")
                        .contains(parserFailure));

        awaitNoThreads("udp-listener-nio-multi-",
                "both failures notwithstanding, the event loops are still released");
    }

    private static java.util.List<String> liveThreadNames(final String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith(prefix))
                .toList();
    }

    /**
     * Polls rather than asserting once. {@code shutdownGracefully().syncUninterruptibly()} returns
     * when the termination future completes, and {@code SingleThreadEventExecutor} completes it
     * from inside the event loop thread's own {@code finally} block — so the thread is briefly
     * still alive, and visible to {@link Thread#getAllStackTraces()}, after the waiter wakes.
     * A single assertion here would flake.
     */
    private static void awaitNoThreads(final String prefix, final String because) {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!liveThreadNames(prefix).isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(liveThreadNames(prefix)).as(because).isEmpty();
    }

    /** @param stopFailure thrown from {@code stop()} when non-null. */
    private static TcpParser tcpParser(final RuntimeException stopFailure) {
        return new TcpParser() {
            @Override
            public Handler accept(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
                return new Handler() {
                    @Override
                    public void inactive() {
                    }

                    @Override
                    public void active() {
                    }

                    @Override
                    public Optional<CompletableFuture<?>> parse(final Instant receivedAt, final ByteBuf buffer) {
                        return Optional.empty();
                    }
                };
            }

            @Override
            public String getName() {
                return "teardown";
            }

            @Override
            public String getDescription() {
                return "teardown";
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
                if (stopFailure != null) {
                    throw stopFailure;
                }
            }
        };
    }

    /** @param stopFailure thrown from {@code stop()} when non-null. */
    private static UdpParser udpParser(final RuntimeException stopFailure) {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "teardown";
            }

            @Override
            public String getDescription() {
                return "teardown";
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
                if (stopFailure != null) {
                    throw stopFailure;
                }
            }
        };
    }
}
