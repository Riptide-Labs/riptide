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
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.Pipeline;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.testsupport.LogCapture;
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
        this.appender = LogCapture.startedAppender();
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

            // Both receivers are configured with port 0, so the kernel picks. Reporting the literal
            // 0 would tell an operator nothing about where the socket actually is — the same "log
            // does not match what is bound" defect this change exists to fix.
            assertThat(messages().stream().filter(m -> m.contains("listening on")).toList())
                    .as("the ephemeral port is resolved, not echoed back as configured")
                    .isNotEmpty()
                    .allSatisfy(m -> {
                        assertThat(m).doesNotContain(":0");
                        final var matcher = java.util.regex.Pattern
                                .compile("listening on \\w+ \\S+:(\\d+)").matcher(m);
                        assertThat(matcher.find()).as("address is parseable in %s", m).isTrue();
                        assertThat(Integer.parseInt(matcher.group(1))).isPositive();
                    });

            final var summaryIndex = lastIndexOfMessageContaining("Listening for flows");
            // Presence first: with the ordering assertion first, a regression that removed the
            // summary entirely reported -1 as an ordering failure and the presence assertion below
            // was unreachable.
            assertThat(summaryIndex)
                    .as("the summary log message must be present")
                    .isGreaterThanOrEqualTo(0);
            // Last, not first: each receiver logs once today, so the two coincide — but a second
            // matching line (a retry, or a mixed success/failure run) would let this pass while the
            // summary actually preceded a receiver line, which is the invariant being pinned.
            final var lastReceiverIndex = Math.max(
                    lastIndexOfMessageContaining("Receiver 'nf5'"),
                    lastIndexOfMessageContaining("Receiver 'nf9'"));
            assertThat(summaryIndex)
                    .as("the summary marks the point every receiver is bound, so it comes last")
                    .isGreaterThan(lastReceiverIndex);
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
            occupied.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final int taken = occupied.getLocalPort();

            final var daemon = daemon(Map.of(
                    "riptide.receivers.ipfixtcp.type", "ipfix",
                    "riptide.receivers.ipfixtcp.transport", "TCP",
                    "riptide.receivers.ipfixtcp.host", "127.0.0.1",
                    "riptide.receivers.ipfixtcp.port", String.valueOf(taken)));

            try {
                // BindException specifically: isInstanceOf(Exception.class) would also pass if the
                // daemon's own error-logging path threw, so it could not tell "aborted for the
                // reason under test" from "aborted for an unrelated reason".
                assertThatThrownBy(() -> daemon.run(new DefaultApplicationArguments()))
                        .as("a receiver that cannot bind must still abort startup")
                        .isInstanceOfSatisfying(Throwable.class, thrown -> {
                            var cause = thrown;
                            while (cause != null && !(cause instanceof java.net.BindException)) {
                                cause = cause.getCause();
                            }
                            assertThat(cause)
                                    .as("a BindException must be in the causal chain of %s", thrown)
                                    .isNotNull();
                        });

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
     * Receivers are empty in the shipped {@code application.properties}, so a fresh install reaches
     * this path legitimately — but it cannot ingest a packet, and the summary line used to announce
     * that as {@code Listening for flows with 0 receivers \\o/}.
     */
    @Test
    void aDaemonWithNoReceiversSaysSoInsteadOfClaimingSuccess() throws Exception {
        final var daemon = daemon(Map.of());

        try {
            daemon.run(new DefaultApplicationArguments());

            assertThat(messages())
                    .as("a daemon that cannot ingest must not report it as success")
                    .noneSatisfy(m -> assertThat(m).contains("Listening for flows"));
            assertThat(events())
                    .as("and must say why it will not ingest")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("No receivers configured");
                    });
        } finally {
            daemon.stop();
        }
    }

    /**
     * The default configuration omits {@code host} — {@code ReceiverConfig.host} has no default, so
     * the listener binds the wildcard. Reading the host back off the channel renders that as
     * {@code 0:0:0:0:0:0:0:0} on a dual-stack JVM, which is materially worse than the {@code *} it
     * replaced in the very line this logging exists to provide. Every other test here pins
     * {@code 127.0.0.1}, the one value for which both renderings agree, which is exactly why that
     * regression was invisible.
     */
    @Test
    void aWildcardBindIsReportedReadablyWithItsResolvedPort() throws Exception {
        final var daemon = daemon(Map.of(
                "riptide.receivers.wildcard.type", "netflow5",
                "riptide.receivers.wildcard.port", "0"));

        try {
            daemon.run(new DefaultApplicationArguments());

            assertThat(messages())
                    .as("a wildcard bind stays readable and still resolves its ephemeral port")
                    .anySatisfy(m -> {
                        assertThat(m).contains("Receiver 'wildcard' listening on UDP *:");
                        assertThat(m).doesNotContain("0:0:0:0");
                        final var matcher = java.util.regex.Pattern
                                .compile("listening on UDP \\*:(\\d+)").matcher(m);
                        assertThat(matcher.find()).as("parseable in %s", m).isTrue();
                        assertThat(Integer.parseInt(matcher.group(1))).isPositive();
                    });
        } finally {
            daemon.stop();
        }
    }

    /**
     * The TCP rendering path had no coverage: the only TCP receiver elsewhere in this class fails to
     * bind, so it takes the not-yet-bound fallback and the success branch could have been broken
     * while every test stayed green.
     */
    @Test
    void aTcpReceiverReportsItsResolvedPort() throws Exception {
        final var daemon = daemon(Map.of(
                "riptide.receivers.tcpok.type", "ipfix",
                "riptide.receivers.tcpok.transport", "TCP",
                "riptide.receivers.tcpok.host", "127.0.0.1",
                "riptide.receivers.tcpok.port", "0"));

        try {
            daemon.run(new DefaultApplicationArguments());

            assertThat(messages())
                    .as("the TCP success branch reports a real bound port")
                    .anySatisfy(m -> {
                        assertThat(m).contains("Receiver 'tcpok' listening on TCP 127.0.0.1:");
                        assertThat(m).doesNotContain(":0");
                    });
        } finally {
            daemon.stop();
        }
    }

    /**
     * The spec scenario "receivers that bound before the failure are still reported" — the log shape
     * the change exists to fix was one that narrated the receiver which worked and stayed silent
     * about the one that did not.
     */
    @Test
    void aSuccessfulAndAFailingReceiverAreBothDistinguishableInTheLog() throws Exception {
        try (var occupied = new ServerSocket()) {
            occupied.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final int taken = occupied.getLocalPort();

            final var daemon = daemon(Map.of(
                    "riptide.receivers.aaa-ok.type", "netflow5",
                    "riptide.receivers.aaa-ok.host", "127.0.0.1",
                    "riptide.receivers.aaa-ok.port", "0",
                    "riptide.receivers.zzz-clash.type", "ipfix",
                    "riptide.receivers.zzz-clash.transport", "TCP",
                    "riptide.receivers.zzz-clash.host", "127.0.0.1",
                    "riptide.receivers.zzz-clash.port", String.valueOf(taken)));

            try {
                // Receiver start order follows HashMap iteration, so which one runs first is not
                // guaranteed. Assert only what must hold either way: if the good one ran, it is
                // reported as listening; the failing one is always reported as failed; and no
                // summary is emitted.
                assertThatThrownBy(() -> daemon.run(new DefaultApplicationArguments()))
                        .as("one receiver failing still aborts startup")
                        .isInstanceOf(Throwable.class);

                assertThat(events())
                        .as("the failing receiver is named as failed, distinguishably")
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(event.getFormattedMessage())
                                    .contains("Receiver 'zzz-clash'")
                                    .contains("failed to start")
                                    .contains(String.valueOf(taken));
                        });

                assertThat(messages())
                        .as("no receiver is both reported listening and reported failed")
                        .noneSatisfy(m -> assertThat(m)
                                .contains("Receiver 'zzz-clash'")
                                .contains("listening on"));

                assertThat(messages())
                        .as("a partially started daemon must not be summarised as listening")
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

        try {
            daemon.run(new DefaultApplicationArguments());
        } catch (final Throwable startFailed) {
            // Guarded like the other tests in this class: if either ephemeral bind fails, the
            // already-started listener's event loops would otherwise survive the JVM and the
            // thread-name assertions in sibling tests would fail confusingly instead of here.
            daemon.stop();
            throw startFailed;
        }
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
                new SessionAdmissionConfig(),
                config);
    }

    private List<String> messages() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<ILoggingEvent> events() {
        return List.copyOf(this.appender.list);
    }

    /** Index of the LAST message containing {@code fragment}, or -1. */
    private int lastIndexOfMessageContaining(final String fragment) {
        final var all = messages();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
