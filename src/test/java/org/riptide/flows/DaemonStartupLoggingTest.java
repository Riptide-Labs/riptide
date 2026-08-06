/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.riptide.config.DaemonConfig;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.pipeline.Pipeline;
import org.riptide.snmp.ExporterInterfaceTable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the log says at startup must match what is bound.
 *
 * <p>The regression this pins (#453): the {@code Listening for flows} summary was emitted from the
 * constructor, while the sockets are bound in {@link Daemon#run}. Those are different lifecycle
 * phases: a bean failing during context refresh means {@code run()} never executes, so the line
 * announced success before anything listened, and on a healthy boot the bind was the last thing to
 * happen and the only thing never logged.
 *
 * <p>The costlier half was attribution: a receiver that failed to bind appeared in the log neither
 * by name nor by port, leaving an operator with {@code Address already in use} and several
 * configured receivers to choose between.
 */
class DaemonStartupLoggingTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void captureDaemonLog() {
        this.logger = (Logger) LoggerFactory.getLogger(Daemon.class);
        this.originalLevel = this.logger.getLevel();
        // Pinned, not inherited: two of the assertions below are negative (no "Listening for flows"),
        // and if ambient test logging were ever raised above INFO they would pass vacuously — the
        // events would simply never be captured. The capture must not depend on configuration this
        // test does not own.
        this.logger.setLevel(Level.INFO);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void releaseDaemonLog() {
        this.logger.detachAppender(this.appender);
        this.appender.stop();
        this.logger.setLevel(this.originalLevel);
    }

    @Test
    void constructingTheDaemonClaimsNothingAboutListening() {
        daemon(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0"));

        assertThat(messages())
                .as("nothing is bound until run(); the constructor must not claim otherwise")
                .noneSatisfy(message -> assertThat(message).contains("Listening for flows"));
    }

    @Test
    void eachReceiverIsReportedWhenItBindsAndThenSummarised() throws Exception {
        final var daemon = daemon(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0",
                "riptide.receivers.nf9.type", "netflow9",
                "riptide.receivers.nf9.host", "127.0.0.1",
                "riptide.receivers.nf9.port", "0"));

        try {
            daemon.run(new DefaultApplicationArguments());

            assertThat(messages())
                    .as("every receiver is named as it binds")
                    .anySatisfy(m -> assertThat(m).contains("Receiver 'nf5'").contains("listening on"))
                    .anySatisfy(m -> assertThat(m).contains("Receiver 'nf9'").contains("listening on"));

            final var summaryIndex = indexOfMessageContaining("Listening for flows");
            final var lastReceiverIndex = Math.max(
                    indexOfMessageContaining("Receiver 'nf5'"),
                    indexOfMessageContaining("Receiver 'nf9'"));
            assertThat(summaryIndex)
                    .as("the summary marks the point every receiver is bound, so it comes last")
                    .isGreaterThan(lastReceiverIndex);
            assertThat(summaryIndex)
                    .as("the summary log message must be present")
                    .isGreaterThanOrEqualTo(0);
            assertThat(messages().get(summaryIndex))
                    .as("its count must match the receivers reported")
                    .contains("2 receivers");
        } finally {
            daemon.stop();
        }
    }

    @Test
    void aReceiverThatCannotBindIsNamedWithItsAddress() throws Exception {
        // The conflict comes from the socket being live and listening: on Linux and macOS a second
        // bind to the same address and port fails with EADDRINUSE regardless of SO_REUSEADDR, which
        // only relaxes TIME_WAIT — sharing a live listener needs SO_REUSEPORT, which neither side
        // sets. Note TcpListener hard-codes SO_REUSEADDR on its own bootstrap, so nothing set here
        // would gate it anyway. (On Windows, SO_REUSEADDR does permit hijacking a live socket, so
        // this test assumes POSIX semantics — as does CI.)
        try (var occupied = new ServerSocket()) {
            occupied.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            final int taken = occupied.getLocalPort();

            final var daemon = daemon(Map.of(
                    "riptide.receivers.ipfixtcp.type", "ipfix",
                    "riptide.receivers.ipfixtcp.transport", "TCP",
                    "riptide.receivers.ipfixtcp.host", "127.0.0.1",
                    "riptide.receivers.ipfixtcp.port", String.valueOf(taken)));

            try {
                assertThatThrownBy(() -> daemon.run(new DefaultApplicationArguments()))
                        .as("a receiver that cannot bind must still abort startup")
                        .isInstanceOf(Exception.class);

                assertThat(events())
                        .as("the failing receiver is named, with its address: a stack alone is not")
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(event.getFormattedMessage())
                                    .contains("Receiver 'ipfixtcp'")
                                    .contains("failed to start")
                                    .contains(String.valueOf(taken));
                        });

                assertThat(messages())
                        .as("a failed start must not be summarised as listening")
                        .noneSatisfy(m -> assertThat(m).contains("Listening for flows"));
            } finally {
                daemon.stop();
            }
        }
    }

    /**
     * A listener dying with an {@code Error} must not take the rest of shutdown with it.
     *
     * <p>{@code stop()} promises that a failing listener cannot keep the pipeline from draining —
     * the batch flusher is a daemon thread, so a skipped drain silently loses the whole buffer. An
     * {@code Error} used to escape the loop and break that promise, skipping every later listener
     * and the drain itself.
     *
     * <p>The failure is injected through the {@code MetricRegistry} the daemon hands its listeners:
     * {@code UdpListener.stop()} deregisters its {@code socketDrops} gauge, so an {@code Error}
     * from {@code remove()} surfaces out of that listener's teardown.
     */
    @Test
    void anErrorStoppingOneListenerStopsNeitherTheOthersNorTheDrain() throws Exception {
        final var armed = new java.util.concurrent.atomic.AtomicBoolean();
        final var registry = new MetricRegistry() {
            @Override
            public boolean remove(final String name) {
                if (armed.get()) {
                    throw new StackOverflowError("listener teardown died");
                }
                return super.remove(name);
            }
        };
        final var pipeline = Mockito.mock(Pipeline.class);
        final var daemon = daemon(Map.of(
                "riptide.receivers.first.type", "netflow5",
                "riptide.receivers.first.host", "127.0.0.1",
                "riptide.receivers.first.port", "0",
                "riptide.receivers.second.type", "netflow5",
                "riptide.receivers.second.host", "127.0.0.1",
                "riptide.receivers.second.port", "0"), pipeline, registry);

        daemon.run(new DefaultApplicationArguments());
        armed.set(true);

        daemon.stop();

        assertThat(liveThreadNames("udp-listener-nio-first-"))
                .as("the listener whose teardown raised an Error is still released")
                .isEmpty();
        assertThat(liveThreadNames("udp-listener-nio-second-"))
                .as("an Error in one listener must not skip the listeners after it")
                .isEmpty();
        Mockito.verify(pipeline).stop();
    }

    private static List<String> liveThreadNames(final String prefix) {
        // Polled: shutdownGracefully().syncUninterruptibly() returns when the termination future
        // completes, which SingleThreadEventExecutor does from inside the event loop thread's own
        // finally block, so the thread is briefly still alive afterwards.
        // Sleeping rather than spinning: getAllStackTraces() walks every thread behind a safepoint,
        // so a tight loop would pin a core and hammer safepoints for the whole deadline on exactly
        // the run where the assertion is about to fail and the output matters most.
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        List<String> alive;
        while (true) {
            alive = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith(prefix))
                    .toList();
            if (alive.isEmpty() || System.nanoTime() >= deadline) {
                return alive;
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return alive;
            }
        }
    }

    private Daemon daemon(final Map<String, Object> properties) {
        return daemon(properties, Mockito.mock(Pipeline.class), new MetricRegistry());
    }

    private Daemon daemon(final Map<String, Object> properties,
                          final Pipeline pipeline,
                          final MetricRegistry registry) {
        final var config = new Binder(new MapConfigurationPropertySource(properties))
                .bind("riptide", DaemonConfig.class)
                .orElseGet(DaemonConfig::new);
        return new Daemon(
                pipeline,
                registry,
                Mockito.mock(ValueConversionService.class),
                Mockito.mock(ValueConversionService.class),
                Mockito.mock(ExporterInterfaceTable.class),
                Mockito.mock(ExporterSamplingTable.class),
                config);
    }

    private List<String> messages() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<ILoggingEvent> events() {
        return List.copyOf(this.appender.list);
    }

    private int indexOfMessageContaining(final String fragment) {
        final var all = messages();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
