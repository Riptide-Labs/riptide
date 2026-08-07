/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.listeners.UdpParser;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.SessionAdmission;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.flows.parser.session.TransactionalSession;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public abstract class UdpParserBase extends ParserBase implements UdpParser {

    /**
     * Datagrams may be dropped when the workers fall behind: the medium is already lossy, nothing is
     * acknowledged, and a counted userspace drop beats pushing back into the kernel receive buffer
     * where the loss cannot be seen. IPFIX/TCP keeps the base class's blocking behaviour.
     */
    @Override
    protected boolean mayDropOnFullQueue() {
        return true;
    }
    public static final long HOUSEKEEPING_INTERVAL = 60000;

    private static final Logger LOG = LoggerFactory.getLogger(UdpParserBase.class);

    private final Meter packetsReceived;
    private final Counter parserErrors;

    private UdpSessionManager sessionManager;

    private ScheduledFuture<?> housekeepingFuture;
    /** Owned, not borrowed — see {@link #start}. */
    private ScheduledExecutorService housekeeper;
    private Duration templateTimeout = Duration.ofMinutes(30);
    private OptionListener optionListener = OptionListener.NONE;
    /**
     * Shared across every parser when the daemon wires one in, so the configured bounds describe
     * the whole collector rather than each receiver separately. The default keeps a
     * standalone-constructed parser bounded rather than unbounded.
     */
    private SessionAdmission sessionAdmission =
            new SessionAdmission(new SessionAdmissionConfig(), new MetricRegistry());

    public UdpParserBase(final Protocol protocol,
                         final String name,
                         final BiConsumer<Source, List<Flow>> dispatcher,
                         final Identity identity,
                         final MetricRegistry metricRegistry) {
        super(protocol, name, dispatcher, identity, metricRegistry);

        this.packetsReceived = metricRegistry.meter(MetricRegistry.name("parsers",  name, "packetsReceived"));
        this.parserErrors = metricRegistry.counter(MetricRegistry.name("parsers",  name, "parserErrors"));

        // sessionCount reported sessionManager.count() — the TEMPLATE total, not the number of
        // exporters — so it has always overstated by however many templates each exporter announces.
        // domainCount() is the quantity the name promises: one per (session, observation domain).
        String sessionCountGauge = MetricRegistry.name("parsers",  name, "sessionCount");
        // Register only if it's not already there in the registry.
        if (!metricRegistry.getGauges().containsKey(sessionCountGauge)) {
            metricRegistry.register(sessionCountGauge, (Gauge<Integer>) () -> (this.sessionManager != null) ? this.sessionManager.domainCount() : null);
        }

        // The old value is still worth having — template cardinality is what drives the parse-path
        // cost — just under a name that says what it is.
        String templateCountGauge = MetricRegistry.name("parsers",  name, "templateCount");
        if (!metricRegistry.getGauges().containsKey(templateCountGauge)) {
            metricRegistry.register(templateCountGauge, (Gauge<Integer>) () -> (this.sessionManager != null) ? this.sessionManager.count() : null);
        }
    }

    protected abstract FlowPacket parse(Session session, ByteBuf buffer) throws Exception;

    protected abstract UdpSessionManager.SessionKey buildSessionKey(InetSocketAddress remoteAddress, InetSocketAddress localAddress);

    @Override
    public final CompletableFuture<?> parse(final Instant receivedAt,
                                            final ByteBuf buffer,
                                            final InetSocketAddress remoteAddress,
                                            final InetSocketAddress localAddress) throws Exception {
        this.packetsReceived.mark();

        final UdpSessionManager.SessionKey sessionKey = this.buildSessionKey(remoteAddress, localAddress);
        final TransactionalSession session = new TransactionalSession(this.sessionManager.getSession(sessionKey));

        final FlowPacket parsed;
        try {
            parsed = this.parse(session, buffer);
        } catch (Exception e) {
            // Discard the malformed message only (RFC 7011 §10.3) — NOT the whole session.
            // Dropping the session here discarded the exporter's templates and sequence state, so
            // a single corrupt packet made all subsequent valid packets unparseable until the
            // exporter re-sent its templates (observed against a buggy pmacct nfprobe exporter,
            // #273). The rollback removes only what THIS packet taught us: packets install
            // templates set-by-set while parsing, so a mis-framed packet may have committed a
            // garbage template before a later set threw — retaining it would silently mis-decode
            // subsequent data sets. Deliberately scoped to the parse phase: a transmit/dispatch
            // failure below says nothing about the packet's templates.
            session.rollback();
            this.parserErrors.inc();
            throw e;
        }
        LOG.trace("Parsed packet: {}", parsed);

        return this.transmit(receivedAt, parsed, session);
    }

    /** Must be set before {@link #start}; the session manager is built there. */
    public void setOptionListener(final OptionListener optionListener) {
        this.optionListener = Objects.requireNonNull(optionListener);
    }

    /** Must be set before {@link #start}; the session manager is built there. */
    public void setSessionAdmission(final SessionAdmission sessionAdmission) {
        this.sessionAdmission = Objects.requireNonNull(sessionAdmission);
    }

    /**
     * Owns the scheduler that sweeps its sessions.
     *
     * <p>Ownership follows the data: this class creates {@code sessionManager} and discards it in
     * {@link #stop}, so it owns the schedule that reaps it. Same shape as
     * {@code InterfaceSnapshotPoller} and {@code GeoIpEnricher} — a named daemon single-thread
     * scheduler, shut down with its owner.
     *
     * <p>Until #459 this method took a {@code ScheduledExecutorService} from its listener and, until
     * #457, scheduled on it. That executor was the listener's event loop group, whose job is
     * draining a socket: {@code scheduleAtFixedRate} dispatches by round-robin, so the sweep could
     * land on the reading thread and turn every execution into a pause in packet reception — kernel
     * loss on {@code socketDrops} rather than an application error. The parameter is gone, so that
     * is no longer a mistake anyone can make.
     */
    @Override
    public void start() {
        super.start();
        this.sessionManager = new UdpSessionManager(this.templateTimeout, this::sequenceNumberTracker,
                this.optionListener, this.sessionAdmission);
        // Daemon: ParserBase.stop() already documents that abandoned non-daemon workers wedge JVM
        // exit, and a reaper killed mid-sweep costs nothing — it reads and prunes, it never writes
        // anything a later sweep cannot redo.
        this.housekeeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "udp-parser-housekeeping-" + getName());
            thread.setDaemon(true);
            return thread;
        });
        // The handle is kept and cancelled explicitly rather than discarded: doHousekeeping throwing
        // would otherwise cancel the schedule silently and leave reaping dead for the process
        // lifetime.
        this.housekeepingFuture = this.housekeeper.scheduleAtFixedRate(this.sessionManager::doHousekeeping,
                HOUSEKEEPING_INTERVAL,
                HOUSEKEEPING_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (this.housekeepingFuture != null) {
            this.housekeepingFuture.cancel(false);
            this.housekeepingFuture = null;
        }
        // Nulled like the future above, so a second stop() is well defined rather than shutting down
        // an already-dead executor.
        if (this.housekeeper != null) {
            this.housekeeper.shutdownNow();
            this.housekeeper = null;
        }

        super.stop();
    }

    public Duration getTemplateTimeout() {
        return this.templateTimeout;
    }

    public void setTemplateTimeout(final Duration templateTimeout) {
        this.templateTimeout = templateTimeout;
    }

    @Override
    public Object dumpInternalState() {
        return this.sessionManager.dumpInternalState();
    }
}
