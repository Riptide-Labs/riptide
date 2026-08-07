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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.riptide.config.DaemonConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.riptide.snmp.ExporterInterfaceTable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code flow-sampling-interval-fallback} must reach NetFlow v5, on every receiver that accepts v5.
 *
 * <p>These assertions are made against a running {@link Daemon} rather than against the flow builder
 * because the defect was in the wiring, not the resolution: {@code Daemon} constructed the v5 parser
 * without the setting while passing the same {@code MultiConfig} value to the v9 and IPFIX parsers
 * beside it. A builder-level test would have stayed green throughout.
 *
 * <p>The v5 half of a {@code multi} receiver is the case that failed silently. A dedicated
 * {@code netflow5} receiver failed loudly instead — the property did not bind at all and startup
 * aborted — so the first test here also pins that the property is merely accepted.
 */
class Netflow5SamplingFallbackTest {

    /** Sampling word {@code 0x0000}: this exporter advertises nothing, so only configuration can speak. */
    private static final String UNSAMPLED_CAPTURE = "/flows/netflow5.dat";

    /** Sampling word {@code 0x03e8}: algorithm 0, interval 1000 — the contested case, from a real MX80. */
    private static final String SAMPLED_CAPTURE = "/flows/netflow5_test_juniper_mx80.dat";

    private static final Pattern LISTENING =
            Pattern.compile("listening on UDP 127\\.0\\.0\\.1:(\\d+)");

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void captureDaemonLog() {
        this.logger = (Logger) LoggerFactory.getLogger(Daemon.class);
        this.originalLevel = this.logger.getLevel();
        // The bound port is only discoverable from this line, so the level must not be inherited.
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
    void dedicatedNetflow5ReceiverAppliesTheConfiguredFallback() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0",
                "riptide.receivers.nf5.flow-sampling-interval-fallback", "1000"));

        assertThat(flows)
                .as("a v5 receiver must honour the only rate setting v5 has")
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1000.0));
    }

    /**
     * The regression this change exists to close. On {@code main} the v5 parser is constructed at
     * {@code Daemon} without the fallback while v9 and IPFIX beside it receive the same value, so
     * this asserted 1.0 and nothing anywhere reported that half the configuration was inert.
     */
    @Test
    void multiReceiverAppliesTheConfiguredFallbackToNetflow5() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.mixed.type", "multi",
                "riptide.receivers.mixed.host", "127.0.0.1",
                "riptide.receivers.mixed.port", "0",
                "riptide.receivers.mixed.flow-sampling-interval-fallback", "1000"));

        assertThat(flows)
                .as("v5 shares the receiver's fallback with v9 and IPFIX, not a private default")
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1000.0));
    }

    @Test
    void withoutAConfiguredFallbackNetflow5StaysUnsampled() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0"));

        assertThat(flows)
                .as("no exporter rate and no configured rate means unsampled")
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1.0));
    }

    /**
     * A configured 1 is an answer, not an absent value. It states this receiver's exporters do not
     * sample, and must survive the same {@code usable()} check that discards 0.
     */
    @Test
    void aConfiguredFallbackOfOneIsHonoured() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0",
                "riptide.receivers.nf5.flow-sampling-interval-fallback", "1"));

        assertThat(flows)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1.0));
    }

    /**
     * The header rung reaches a dedicated v5 receiver. This capture advertises 1:1000 with the mode
     * bits clear, so it is the contested case, resolved by default.
     */
    @Test
    void dedicatedNetflow5ReceiverReadsTheHeaderRate() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0"), SAMPLED_CAPTURE);

        assertThat(flows)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1000.0));
    }

    /** And the v5 half of a multi receiver, the path that silently dropped the fallback. */
    @Test
    void multiReceiverReadsTheHeaderRateForNetflow5() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.mixed.type", "multi",
                "riptide.receivers.mixed.host", "127.0.0.1",
                "riptide.receivers.mixed.port", "0"), SAMPLED_CAPTURE);

        assertThat(flows)
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1000.0));
    }

    /** The opt-out reaches the parser too, or it is not an opt-out. */
    @Test
    void pinningTheHeaderOffReachesTheParser() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.nf5.type", "netflow5",
                "riptide.receivers.nf5.host", "127.0.0.1",
                "riptide.receivers.nf5.port", "0",
                "riptide.receivers.nf5.trust-header-sampling-interval", "false"), SAMPLED_CAPTURE);

        assertThat(flows)
                .as("the exporter states 1000 with the mode bits clear; the operator pinned it off")
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1.0));
    }

    /**
     * The opt-out on the v5 half of a `multi` receiver, end to end.
     *
     * <p>Without this, replacing the multi branch's {@code withTrustHeaderSamplingInterval(...)}
     * with a hardcoded {@code true} passes every other test in the change — which is precisely the
     * shape of the wiring defect the preceding change existed to fix.
     */
    @Test
    void pinningTheHeaderOffReachesTheMultiReceiversNetflow5Half() throws Exception {
        final var flows = flowsFromDaemonConfigured(Map.of(
                "riptide.receivers.mixed.type", "multi",
                "riptide.receivers.mixed.host", "127.0.0.1",
                "riptide.receivers.mixed.port", "0",
                "riptide.receivers.mixed.trust-header-sampling-interval", "false"), SAMPLED_CAPTURE);

        assertThat(flows)
                .as("the setting must reach v5 on a multi receiver, not only on a dedicated one")
                .isNotEmpty()
                .allSatisfy(flow -> assertThat(flow.getSamplingInterval()).isEqualTo(1.0));
    }

    /** Starts a daemon on an ephemeral port, sends one v5 capture at it, and returns what it built. */
    private List<Flow> flowsFromDaemonConfigured(final Map<String, Object> properties) throws Exception {
        return flowsFromDaemonConfigured(properties, UNSAMPLED_CAPTURE);
    }

    private List<Flow> flowsFromDaemonConfigured(final Map<String, Object> properties,
                                                 final String capture) throws Exception {
        final var pipeline = Mockito.mock(Pipeline.class);
        final var daemon = daemon(properties, pipeline);

        try {
            daemon.run(new DefaultApplicationArguments());
            send(boundPort(), payload(capture));
            return awaitDispatchedFlows(pipeline);
        } finally {
            daemon.stop();
        }
    }

    private static byte[] payload(final String capture) throws Exception {
        final var url = Netflow5SamplingFallbackTest.class.getResource(capture);
        return Files.readAllBytes(Paths.get(url.toURI()));
    }

    private static void send(final int port, final byte[] payload) throws Exception {
        try (var socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(
                    payload, payload.length, InetAddress.getByName("127.0.0.1"), port));
        }
    }

    /** The kernel picked the port, so the startup log is the only place it is stated. */
    private int boundPort() {
        return this.appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(LISTENING::matcher)
                .filter(java.util.regex.Matcher::find)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no receiver reported a bound port; log was " + this.appender.list));
    }

    /** Ingest is asynchronous, so poll rather than assume the packet has been handled on return. */
    @SuppressWarnings("unchecked")
    private static List<Flow> awaitDispatchedFlows(final Pipeline pipeline) throws Exception {
        final var captor = ArgumentCaptor.forClass(List.class);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            try {
                Mockito.verify(pipeline, Mockito.atLeastOnce())
                        .process(Mockito.any(Source.class), captor.capture());
                return (List<Flow>) captor.getValue();
            } catch (final AssertionError notYet) {
                Thread.sleep(25);
            }
        }
        throw new AssertionError("no flows reached the pipeline within 10s");
    }

    private Daemon daemon(final Map<String, Object> properties, final Pipeline pipeline) {
        final var config = new Binder(new MapConfigurationPropertySource(properties))
                .bind("riptide", DaemonConfig.class)
                .orElseGet(DaemonConfig::new);
        return new Daemon(
                pipeline,
                new MetricRegistry(),
                Mockito.mock(ValueConversionService.class),
                Mockito.mock(ValueConversionService.class),
                Mockito.mock(ExporterInterfaceTable.class),
                Mockito.mock(ExporterSamplingTable.class),
                new SessionAdmissionConfig(),
                config);
    }
}
