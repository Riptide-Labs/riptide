/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows;

import com.google.common.util.concurrent.RateLimiter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.DaemonConfig;
import org.riptide.config.ReceiverConfig;
import org.riptide.flows.listeners.Listener;
import org.riptide.flows.listeners.TcpListener;
import org.riptide.flows.listeners.UdpListener;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.listeners.multi.DispatchingUdpParser;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ipfix.IpfixTcpParser;
import org.riptide.flows.parser.ipfix.IpfixUdpParser;
import org.riptide.flows.parser.netflow5.Netflow5UdpParser;
import org.riptide.flows.parser.sflow.SflowUdpParser;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.pipeline.FlowException;
import org.riptide.pipeline.Pipeline;
import org.riptide.flows.parser.session.ExporterSamplingTable;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.pipeline.Source;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class Daemon implements ApplicationRunner {

    private final List<Listener> listeners;
    private final Pipeline pipeline;

    // Set once the receivers have been started, so health checks can tell "still booting" (live but
    // not ready) apart from "a started receiver has died" (not live).
    private volatile boolean started;

    public Daemon(final Pipeline pipeline,
                  final MetricRegistry metricRegistry,
                  @Qualifier("ipfixValueConversionService") final ValueConversionService ipfixValueConversionService,
                  @Qualifier("netflow9ValueConversionService") final ValueConversionService netflow9ValueConversionService,
                  final ExporterInterfaceTable exporterInterfaceTable,
                  final ExporterSamplingTable exporterSamplingTable,
                  final DaemonConfig config) {
        final var identity = config.resolveIdentity();
        // One option stream, two readers: interface names and sampler rates.
        final OptionListener optionListener = OptionListener.of(exporterInterfaceTable, exporterSamplingTable);

        this.pipeline = Objects.requireNonNull(pipeline);
        // A packet's records are dispatched as one batch (see ParserBase#transmit), so a failure
        // here now costs the whole packet rather than a single flow. That makes swallowing it
        // quietly unacceptable and rethrowing it worse: the previous RuntimeException travelled up
        // into the dispatch task, where it was logged as "Error preparing records for dispatch" with
        // no count and no exporter attribution.
        //
        // Count it and name the exporter instead, following the convention BatchingFlowRepository
        // set: a poison batch must not wedge the pipeline, and loss must never be silent.
        final Counter dispatchErrors = metricRegistry.counter(MetricRegistry.name("pipeline", "dispatchErrors"));
        // One warning per 10s, like the parser's drop path: a persistently failing enricher at a few
        // thousand packets/s would otherwise emit a stack trace per packet, synchronously on the
        // worker, making logging the bottleneck and filling the disk.
        final RateLimiter errorWarnLimiter = RateLimiter.create(0.1);
        final BiConsumer<Source, List<Flow>> dispatcher = (source, flows) -> {
            try {
                this.pipeline.process(source, flows);
            } catch (final FlowException | RuntimeException e) {
                // RuntimeException too, not just FlowException: a shut-down SNMP pool throws
                // RejectedExecutionException and any enricher can NPE. Those used to escape into
                // the dispatch task and be logged as
                // "Error preparing records for dispatch" with no counter and no exporter — the exact
                // hole this block exists to close. A packet's worth of loss must not be silent.
                dispatchErrors.inc(flows.size());
                if (log.isWarnEnabled() && errorWarnLimiter.tryAcquire()) {
                    // Deliberately not "enrichment failed": with batching disabled a FlowException
                    // also arrives from the persist path, and mislabelling it misdirects diagnosis.
                    log.warn("Dropping {} flows from {}: {}", flows.size(), source.identity(),
                            e.getMessage(), e);
                }
            }
        };

        this.listeners = config.getReceivers().entrySet().stream()
                .map(e -> e.getValue().accept(new ReceiverConfig.Cases<Listener>() {
                    @Override
                    public Listener match(final ReceiverConfig.Neflow5Config config) {
                        final var parser = new Netflow5UdpParser(e.getKey(), dispatcher, identity, metricRegistry);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }

                    @Override
                    public Listener match(final ReceiverConfig.Neflow9Config config) {
                        final var parser = new Netflow9UdpParser(e.getKey(), dispatcher, identity, metricRegistry, netflow9ValueConversionService)
                                .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                .withSamplingTable(exporterSamplingTable);
                        parser.setOptionListener(optionListener);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }

                    @Override
                    public Listener match(final ReceiverConfig.IpfixConfig config) {
                        return switch (config.getTransport()) {
                            case UDP -> {
                                final var parser = new IpfixUdpParser(e.getKey(), dispatcher, identity, metricRegistry, ipfixValueConversionService)
                                        .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                        .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());

                                parser.setOptionListener(optionListener);

                                yield new UdpListener(e.getKey(), parser, metricRegistry)
                                        .withPort(config.getPort())
                                        .withHost(config.getHost());
                            }
                            case TCP -> {
                                final var parser = new IpfixTcpParser(e.getKey(), dispatcher, identity, metricRegistry, ipfixValueConversionService)
                                        .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                        .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());

                                parser.setOptionListener(optionListener);

                                yield new TcpListener(e.getKey(), parser, metricRegistry)
                                        .withPort(config.getPort())
                                        .withHost(config.getHost());
                            }
                        };
                    }

                    @Override
                    public Listener match(final ReceiverConfig.SflowConfig config) {
                        final var parser = new SflowUdpParser(e.getKey(), dispatcher, identity, metricRegistry);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }

                    @Override
                    public Listener match(final ReceiverConfig.MultiConfig config) {
                        final var parsers = new HashSet<DispatchableUdpParser>();

                        if (config.isNetflow5()) {
                            parsers.add(new Netflow5UdpParser(e.getKey() + ":netflow5", dispatcher, identity, metricRegistry));
                        }

                        if (config.isSflow()) {
                            parsers.add(new SflowUdpParser(e.getKey() + ":sflow", dispatcher, identity, metricRegistry));
                        }

                        if (config.isNetflow9()) {
                            final var netflow9 = new Netflow9UdpParser(e.getKey() + ":netflow9", dispatcher, identity, metricRegistry, netflow9ValueConversionService)
                                    .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                    .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                    .withSamplingTable(exporterSamplingTable);
                            netflow9.setOptionListener(optionListener);
                            parsers.add(netflow9);
                        }

                        if (config.isIpfix()) {
                            final var ipfix = new IpfixUdpParser(e.getKey() + ":ipfix", dispatcher, identity, metricRegistry, ipfixValueConversionService)
                                    .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                    .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());
                            ipfix.setOptionListener(optionListener);
                            parsers.add(ipfix);
                        }

                        final var parser = new DispatchingUdpParser(e.getKey(), parsers);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }
                })).toList();
    }

    // Start-time logging lives here rather than in each Listener: only the call site holds the
    // receiver's name *and* can observe its failure: a listener that throws from start() cannot
    // log its own. A future Listener should not add its own bind line and duplicate this.
    //
    // These lines necessarily follow Spring's "Started RiptideApplication", which is logged before
    // callRunners(). That reads oddly but is honest; the alternative is binding during context
    // refresh via SmartLifecycle, which would move isStarted() and the health semantics with it.
    // Do NOT "fix" the ordering by moving the summary back into the constructor: that was #453,
    // where it claimed to be listening before anything was bound.
    @Override
    public void run(final ApplicationArguments args) throws Exception {
        this.pipeline.start();

        for (final var listener : this.listeners) {
            try {
                listener.start();
            } catch (final Throwable t) {
                // Named here because the propagating stack identifies the transport but neither the
                // configured receiver nor its port. Message only: Spring prints the full trace as
                // the exception leaves run(), and a second copy is noise at the moment the operator
                // is trying to read. Rethrown rather than skipped: /readyz checks every listener,
                // so continuing would yield a running-but-never-ready daemon.
                //
                // Throwable, not Exception: a missing native transport surfaces as
                // NoClassDefFoundError and starting the event loop threads can raise
                // OutOfMemoryError. Narrowing to Exception would let exactly those aborts through
                // with the unattributed stack this logging exists to replace. Rethrown immediately,
                // so nothing is swallowed — precise rethrow keeps the declared type unchanged.
                final var reason = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                log.error("Receiver '{}' failed to start on {}: {}",
                        listener.getName(), listener.getDescription(), reason);
                throw t;
            }
            log.info("Receiver '{}' listening on {}", listener.getName(), listener.getDescription());
        }

        this.started = true;
        log.info("Listening for flows with {} receivers \\o/", this.listeners.size());
    }

    @PreDestroy
    public void stop() throws Exception {
        this.started = false;
        // Listeners first: the parsers stop accepting packets and finish their in-flight
        // dispatches, so the pipeline's shutdown drain below sees every accepted flow. A
        // failing listener must not keep the pipeline (and its batch drain) from stopping —
        // the flusher is a daemon thread, so a skipped drain silently loses the whole buffer.
        // Exception, not RuntimeException: Listener.stop() declares no checked exceptions, but
        // the Netty listeners call syncUninterruptibly(), which sneaky-throws the future's
        // cause — checked exceptions included. Do not "tidy" this back to RuntimeException.
        for (final var listener : this.listeners) {
            try {
                listener.stop();
            } catch (final Exception e) {
                log.warn("Failed to stop listener {}", listener.getName(), e);
            }
        }
        this.pipeline.stop();
    }

    /** The configured receivers, for health reporting. */
    public List<Listener> getListeners() {
        return this.listeners;
    }

    /** Whether the receivers have been started (i.e. {@link #run} has completed). */
    public boolean isStarted() {
        return this.started;
    }
}
