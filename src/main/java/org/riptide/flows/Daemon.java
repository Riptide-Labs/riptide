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
import org.riptide.flows.parser.session.SessionAdmission;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
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
                  final SessionAdmissionConfig sessionAdmissionConfig,
                  final DaemonConfig config) {
        final var identity = config.resolveIdentity();
        // One option stream, two readers: interface names and sampler rates — and a meter for the
        // records neither of them claims, which is the only place that gap is visible (#599).
        final OptionListener optionListener =
                OptionListener.of(metricRegistry, exporterInterfaceTable, exporterSamplingTable);
        // One oracle for every receiver, so the configured bounds describe the collector's total
        // retained session state. Per-parser instances would silently multiply the ceiling by the
        // number of configured receivers and register colliding gauges.
        final SessionAdmission sessionAdmission = new SessionAdmission(sessionAdmissionConfig, metricRegistry);

        this.pipeline = Objects.requireNonNull(pipeline);
        final BiConsumer<Source, List<Flow>> dispatcher = dispatcherFor(pipeline, metricRegistry);

        this.listeners = config.getReceivers().entrySet().stream()
                .map(e -> e.getValue().accept(new ReceiverConfig.Cases<Listener>() {
                    @Override
                    public Listener match(final ReceiverConfig.Neflow5Config config) {
                        final var parser = new Netflow5UdpParser(e.getKey(), dispatcher, identity, metricRegistry)
                                .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                .withTrustHeaderSamplingInterval(config.isTrustHeaderSamplingInterval());

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
                        parser.setSessionAdmission(sessionAdmission);

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
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                        .withSamplingTable(exporterSamplingTable);

                                parser.setOptionListener(optionListener);
                        parser.setSessionAdmission(sessionAdmission);

                                yield new UdpListener(e.getKey(), parser, metricRegistry)
                                        .withPort(config.getPort())
                                        .withHost(config.getHost());
                            }
                            case TCP -> {
                                final var parser = new IpfixTcpParser(e.getKey(), dispatcher, identity, metricRegistry, ipfixValueConversionService)
                                        .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                        .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                        .withSamplingTable(exporterSamplingTable);

                                // No admission oracle here: IPFIX over TCP keeps per-connection
                                // state in TcpSession, and a connection is already a bounded,
                                // handshake-verified resource. The unbounded growth this guards
                                // against is specific to connectionless UDP.
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
                            parsers.add(new Netflow5UdpParser(e.getKey() + ":netflow5", dispatcher, identity, metricRegistry)
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                    .withTrustHeaderSamplingInterval(config.isTrustHeaderSamplingInterval()));
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
                            netflow9.setSessionAdmission(sessionAdmission);
                            parsers.add(netflow9);
                        }

                        if (config.isIpfix()) {
                            final var ipfix = new IpfixUdpParser(e.getKey() + ":ipfix", dispatcher, identity, metricRegistry, ipfixValueConversionService)
                                    .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                    .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback())
                                    .withSamplingTable(exporterSamplingTable);
                            ipfix.setOptionListener(optionListener);
                            ipfix.setSessionAdmission(sessionAdmission);
                            parsers.add(ipfix);
                        }

                        final var parser = new DispatchingUdpParser(e.getKey(), parsers);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }
                })).toList();
    }

    /**
     * The dispatcher every parser is handed: it runs the pipeline and turns a failure into a
     * counted, attributed drop.
     *
     * <p>A packet's records are dispatched as one batch (see {@code ParserBase#transmit}), so a
     * failure here costs the whole packet rather than a single flow. That makes swallowing it
     * quietly unacceptable and rethrowing it worse: the previous {@code RuntimeException} travelled
     * up into the dispatch task, where it was logged as "Error preparing records for dispatch" with
     * no count and no exporter attribution. Count it and name the exporter instead, following the
     * convention {@code BatchingFlowRepository} set: a poison batch must not wedge the pipeline,
     * and loss must never be silent.</p>
     *
     * <p><b>Because it returns normally on the failure path,</b> {@code ParserBase} marks those
     * records dispatched — so {@code parsers.*.recordsDispatched} counts records this dispatcher
     * dropped, and delivery is {@code recordsScheduled − dispatchDrops − dispatchErrors}, exactly
     * as the operations guide says. That is not a defect, but it is the whole reason this factory
     * exists rather than an inline lambda: a test that imitated this shape with a rethrowing stub
     * asserted the opposite and passed from #391 until #723 found it.
     * {@code DaemonDispatcherTest} now drives this method, so a change of heart about rethrowing
     * cannot leave the assertion still green.</p>
     *
     * @param metricRegistry also the owner of {@code pipeline.dispatchErrors}; a second call
     *                       against the same registry returns a dispatcher sharing that counter,
     *                       which is what makes the counts comparable across receivers
     */
    static BiConsumer<Source, List<Flow>> dispatcherFor(final Pipeline pipeline,
                                                        final MetricRegistry metricRegistry) {
        final Counter dispatchErrors = metricRegistry.counter(MetricRegistry.name("pipeline", "dispatchErrors"));
        // One warning per 10s, like the parser's drop path: a persistently failing enricher at a few
        // thousand packets/s would otherwise emit a stack trace per packet, synchronously on the
        // worker, making logging the bottleneck and filling the disk.
        final RateLimiter errorWarnLimiter = RateLimiter.create(0.1);
        return (source, flows) -> {
            try {
                pipeline.process(source, flows);
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
                // Type as well as message: a NoClassDefFoundError — one of the two cases the
                // Throwable catch exists for — carries a message like "io/netty/.../Epoll" that
                // reads as a config problem unless the type is shown. Blank counts as absent:
                // several construction paths carry an empty message, which would otherwise print
                // as a bare trailing colon and lose the cause entirely.
                final var message = t.getMessage();
                final var reason = message != null && !message.isBlank()
                        ? t.getClass().getSimpleName() + ": " + message
                        : t.getClass().getName();
                log.error("Receiver '{}' failed to start on {}: {}",
                        listener.getName(), listener.getDescription(), reason);
                // Rethrown without releasing the receivers that already bound, which is safe only
                // because of what happens next: Spring's handleRunFailure closes the context when a
                // runner throws, which drives @PreDestroy stop() and releases them. That is a real
                // dependency on framework behaviour, so it is written down rather than assumed —
                // outside Spring, or if that ever changes, this leaks every listener started before
                // the failing one. stop() tolerates a partial start for exactly this reason.
                throw t;
            }
            log.info("Receiver '{}' listening on {}", listener.getName(), listener.getDescription());
        }

        this.started = true;
        if (this.listeners.isEmpty()) {
            // Not an error: receivers are empty in the shipped application.properties, so a fresh
            // install reaches here legitimately. But "Listening for flows with 0 receivers \\o/"
            // reads as success, and this process cannot ingest a packet — say so instead.
            //
            // Readiness deliberately still reports UP. Nothing is broken, and a collector that has
            // not been given receivers yet is misconfigured rather than unhealthy; failing readiness
            // would turn the default configuration into a pod that never becomes ready.
            log.warn("No receivers configured — this daemon will not ingest any flows. "
                    + "Configure riptide.receivers.<name> to receive.");
        } else {
            log.info("Listening for flows with {} receivers \\o/", this.listeners.size());
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        this.started = false;
        // Listeners first: the parsers stop accepting packets and finish their in-flight
        // dispatches, so the pipeline's shutdown drain below sees every accepted flow. A
        // failing listener must not keep the pipeline (and its batch drain) from stopping —
        // the flusher is a daemon thread, so a skipped drain silently loses the whole buffer.
        // Throwable, not Exception and emphatically not RuntimeException. Listener.stop() declares
        // no checked exceptions, but the Netty listeners call syncUninterruptibly(), which
        // sneaky-throws the future's cause — checked exceptions included. Do not "tidy" this back
        // to RuntimeException.
        //
        // Throwable widens it further so the promise above actually holds: an Error (a listener's
        // event loop dying with OutOfMemoryError, a missing native transport surfacing as
        // NoClassDefFoundError) used to escape this loop, skipping every later listener *and* the
        // pipeline drain below — silently losing the whole batch buffer, which is the one outcome
        // this method exists to prevent. Shutdown is best-effort per listener for the same reason
        // teardown is best-effort per resource inside them (see Teardown).
        // The drain must run whatever the loop does, but a plain finally would let a failing drain
        // replace the loop's throwable outright and lose it. Only log.warn can throw from the loop
        // body, so this is a narrow case — and a lost root cause during shutdown is exactly the
        // sort of thing nobody can reconstruct afterwards.
        Throwable primary = null;
        try {
            for (final var listener : this.listeners) {
                try {
                    listener.stop();
                } catch (final Throwable t) {
                    log.warn("Failed to stop listener {}", listener.getName(), t);
                }
            }
        } catch (final Throwable t) {
            primary = t;
        }

        try {
            this.pipeline.stop();
        } catch (final Throwable t) {
            if (primary == null) {
                primary = t;
            } else if (t instanceof Error && !(primary instanceof Error)) {
                // A JVM-level failure outranks a routine shutdown exception. Without this an
                // OutOfMemoryError in the drain would be attached beneath a listener's
                // IllegalStateException and read as the lesser problem in Spring's shutdown log.
                t.addSuppressed(primary);
                primary = t;
            } else {
                primary.addSuppressed(t);
            }
        }

        // Rethrown by type rather than cast. `primary` is a Throwable, and a cast to Exception
        // would raise ClassCastException on anything that is neither Exception nor Error —
        // discarding the cause this whole block exists to preserve, which is the failure being
        // fixed rather than a new one.
        if (primary instanceof Error error) {
            throw error;
        }
        if (primary instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (primary instanceof Exception checked) {
            throw checked;
        }
        if (primary != null) {
            throw new IllegalStateException("listener shutdown failed", primary);
        }
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
