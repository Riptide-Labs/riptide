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
 * phases — a bean failing during context refresh means {@code run()} never executes — so the line
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

    @BeforeEach
    void captureDaemonLog() {
        this.logger = (Logger) LoggerFactory.getLogger(Daemon.class);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void releaseDaemonLog() {
        this.logger.detachAppender(this.appender);
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
            assertThat(messages().get(summaryIndex))
                    .as("its count must match the receivers reported")
                    .contains("2 receivers");
        } finally {
            daemon.stop();
        }
    }

    @Test
    void aReceiverThatCannotBindIsNamedWithItsAddress() throws Exception {
        // reuseAddress(false) so the conflict is real: Java enables SO_REUSEADDR on ServerSocket by
        // default on some platforms, which lets a second bind succeed and the test silently pass.
        try (var occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
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
                        .as("the failing receiver is named, with its address — a stack alone is not")
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

    private Daemon daemon(final Map<String, Object> properties) {
        final var config = new Binder(new MapConfigurationPropertySource(properties))
                .bind("riptide", DaemonConfig.class)
                .orElseGet(DaemonConfig::new);
        return new Daemon(
                Mockito.mock(Pipeline.class),
                new MetricRegistry(),
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
