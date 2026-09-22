/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The startup wait against a real ClickHouse (#833). {@code StartupWaitTest} pins the loop's policy
 * with a scripted probe; this pins what the real probe calls an answer and what it calls silence,
 * which no scripted probe can, and it is the test that proves {@code riptide.clickhouse.startup-wait}
 * is read: the silent case sets the key and observes the failure at that bound.
 *
 * <p>The "late backend" is the running container behind a TCP relay that does not bind its port
 * until a delay has passed. Until then a connection is refused, which is what a collector sees when
 * its backend container has not started; a bound-but-unaccepted socket would instead be accepted
 * into the kernel backlog and hang the probe until its timeout, which is a different case.</p>
 */
@Testcontainers
class ClickhouseStartupWaitIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    private Logger waitLog;
    private ListAppender<ILoggingEvent> waitEvents;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        this.waitLog = (Logger) LoggerFactory.getLogger(StartupWait.class);
        this.originalLevel = this.waitLog.getLevel();
        this.waitLog.setLevel(Level.INFO);
        this.waitEvents = LogCapture.startedAppender();
        this.waitLog.addAppender(this.waitEvents);
    }

    @AfterEach
    void releaseLog() {
        this.waitLog.detachAppender(this.waitEvents);
        this.waitLog.setLevel(this.originalLevel);
    }

    private static String containerEndpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    private static ClickhouseConfig config(final String endpoint, final String password, final String database) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint);
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of(password));
        config.setDatabase(database);
        config.setAsyncInserts(false);
        return config;
    }

    private static ClickhouseRepository repository(final ClickhouseConfig config) {
        return new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
    }

    /** A local port nothing listens on: bound to learn the number, then closed. */
    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private List<String> warnings() {
        return this.waitEvents.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @Timeout(60)
    void aLateBackendInsideTheWindowIsTolerated() throws Exception {
        final int port = closedPort();
        try (var relay = new DelayedRelay(port, CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123),
                Duration.ofSeconds(3))) {
            final var config = config("http://127.0.0.1:" + port, "riptide", "late");

            // Default window: 30 s. The relay binds after 3 s, so the probe is refused at 0 s and
            // 2 s and answered at 4 s.
            repository(config).start();

            Assertions.assertThat(warnings())
                    .as("each refused probe is logged, naming the endpoint")
                    .isNotEmpty()
                    .allSatisfy(message -> Assertions.assertThat(message)
                            .contains("http://127.0.0.1:" + port).contains("did not answer"));
            Assertions.assertThat(this.waitEvents.list)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement().asString().contains("answered after");
            Assertions.assertThat(relay.accepted()).as("the probe and the DDL went through the relay").isPositive();
        }

        // And start() did what it does after the wait: manage mode created the schema.
        try (var admin = new Client.Builder().addEndpoint(containerEndpoint())
                .setUsername("riptide").setPassword("riptide").build()) {
            Assertions.assertThat(admin.queryAll("EXISTS TABLE `late`.flows").getFirst().getLong("result"))
                    .isEqualTo(1);
        }
    }

    @Test
    @Timeout(30)
    void aBackendStillSilentAfterTheWindowFailsStartupNamingTheKey() throws Exception {
        final int port = closedPort();
        final var config = config("http://127.0.0.1:" + port, "riptide", "riptide");
        // The read of the key: a 1 s window, observed as the bound the failure arrives at.
        config.setStartupWait(Duration.ofSeconds(1));

        final Instant before = Instant.now();
        Assertions.assertThatThrownBy(() -> repository(config).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http://127.0.0.1:" + port)
                .hasMessageContaining("within PT1S")
                .hasMessageContaining("2 attempt(s)")
                .hasMessageContaining("riptide.clickhouse.startup-wait");
        Assertions.assertThat(Duration.between(before, Instant.now()))
                .as("the window was honoured, not cut short")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        Assertions.assertThat(warnings()).hasSize(1);
    }

    @Test
    @Timeout(30)
    void aZeroWindowFailsAfterOneProbe() throws Exception {
        final var config = config("http://127.0.0.1:" + closedPort(), "riptide", "riptide");
        config.setStartupWait(Duration.ZERO);

        Assertions.assertThatThrownBy(() -> repository(config).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("within PT0S")
                .hasMessageContaining("1 attempt(s)")
                .hasMessageContaining("riptide.clickhouse.startup-wait");
        Assertions.assertThat(warnings()).isEmpty();
    }

    @Test
    @Timeout(30)
    void aNegativeWindowIsRejectedAtConstruction() {
        final var config = config(containerEndpoint(), "riptide", "riptide");
        config.setStartupWait(Duration.ofSeconds(-1));

        Assertions.assertThatThrownBy(() -> repository(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.clickhouse.startup-wait");
    }

    @Test
    @Timeout(30)
    void aServerThatRefusesTheCredentialIsAnAnswerNotSilence() {
        final var config = config(containerEndpoint(), "wrong", "riptide");

        // Default window of 30 s, and a @Timeout of 30 s on the test: if the refusal were retried
        // as silence, this would not fail with the credential message, it would fail on time.
        //
        // The message is pinned, not the type. In manage mode the refusal reaches the operator as
        // the client's bare ServerException out of ensureDatabase's SneakyThrows get(), which is
        // what it was before the wait existed; the wait's promise is only that the refusal is not
        // mistaken for silence, and the shape of the existing failure is not this test's to change.
        final Instant before = Instant.now();
        Assertions.assertThatThrownBy(() -> repository(config).start())
                .hasMessageContaining(ClickhouseServerErrors.AUTHENTICATION_FAILED_MESSAGE_PREFIX)
                .hasMessageNotContaining("did not answer");
        Assertions.assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(10));
        Assertions.assertThat(warnings()).isEmpty();
    }

    @Test
    @Timeout(30)
    void anUnknownDatabaseInManageModeIsAnAnswerAndGetsCreated() throws Exception {
        // The probe goes through a client pinned to a database that is not there yet, so the
        // server answers UNKNOWN_DATABASE. That is an answer: the wait ends, ensureDatabase runs.
        final var config = config(containerEndpoint(), "riptide", "fresh_" + System.nanoTime());
        config.setManageSchema(true);

        repository(config).start();

        Assertions.assertThat(warnings()).isEmpty();
        try (var admin = new Client.Builder().addEndpoint(containerEndpoint())
                .setUsername("riptide").setPassword("riptide").build()) {
            Assertions.assertThat(admin.queryAll("EXISTS DATABASE `" + config.getDatabase() + "`")
                    .getFirst().getLong("result")).isEqualTo(1);
        }
    }

    /**
     * A TCP relay to the container that binds its port only after a delay. Before that a connection
     * to the port is refused, which is the "backend not up yet" a collector sees. After it, every
     * accepted connection is pumped byte-for-byte to the target in both directions.
     */
    private static final class DelayedRelay implements AutoCloseable {
        private final ExecutorService pumps = Executors.newCachedThreadPool();
        private final List<Socket> sockets = new ArrayList<>();
        private final Thread acceptor;
        private volatile ServerSocket server;
        private volatile int accepted;

        DelayedRelay(final int port, final String targetHost, final int targetPort, final Duration delay) {
            this.acceptor = new Thread(() -> {
                try {
                    Thread.sleep(delay.toMillis());
                    this.server = new ServerSocket(port);
                    while (!Thread.currentThread().isInterrupted()) {
                        final Socket client = this.server.accept();
                        final Socket target = new Socket(targetHost, targetPort);
                        synchronized (this.sockets) {
                            this.sockets.add(client);
                            this.sockets.add(target);
                        }
                        this.accepted++;
                        this.pumps.execute(() -> pump(client, target));
                        this.pumps.execute(() -> pump(target, client));
                    }
                } catch (final InterruptedException | IOException e) {
                    // Closed by close(), or torn down: either way the relay is done.
                }
            }, "delayed-relay");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        int accepted() {
            return this.accepted;
        }

        private static void pump(final Socket from, final Socket to) {
            try {
                final InputStream in = from.getInputStream();
                final OutputStream out = to.getOutputStream();
                in.transferTo(out);
                to.shutdownOutput();
            } catch (final IOException e) {
                // One side went away; the other pump sees EOF and ends too.
            }
        }

        @Override
        public void close() throws IOException {
            this.acceptor.interrupt();
            if (this.server != null) {
                this.server.close();
            }
            synchronized (this.sockets) {
                for (final Socket socket : this.sockets) {
                    socket.close();
                }
            }
            this.pumps.shutdownNow();
        }
    }
}
