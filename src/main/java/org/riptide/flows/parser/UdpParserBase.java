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
import org.riptide.flows.parser.session.TransactionalSession;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public abstract class UdpParserBase extends ParserBase implements UdpParser {
    public static final long HOUSEKEEPING_INTERVAL = 60000;

    private static final Logger LOG = LoggerFactory.getLogger(UdpParserBase.class);

    private final Meter packetsReceived;
    private final Counter parserErrors;

    private UdpSessionManager sessionManager;

    private ScheduledFuture<?> housekeepingFuture;
    private Duration templateTimeout = Duration.ofMinutes(30);
    private OptionListener optionListener = OptionListener.NONE;

    public UdpParserBase(final Protocol protocol,
                         final String name,
                         final BiConsumer<Source, Flow> dispatcher,
                         final Identity identity,
                         final MetricRegistry metricRegistry) {
        super(protocol, name, dispatcher, identity, metricRegistry);

        this.packetsReceived = metricRegistry.meter(MetricRegistry.name("parsers",  name, "packetsReceived"));
        this.parserErrors = metricRegistry.counter(MetricRegistry.name("parsers",  name, "parserErrors"));

        String sessionCountGauge = MetricRegistry.name("parsers",  name, "sessionCount");
        // Register only if it's not already there in the registry.
        if (!metricRegistry.getGauges().containsKey(sessionCountGauge)) {
            metricRegistry.register(sessionCountGauge, (Gauge<Integer>) () -> (this.sessionManager != null) ? this.sessionManager.count() : null);
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

    @Override
    public void start(final ScheduledExecutorService executorService) {
        super.start(executorService);
        this.sessionManager = new UdpSessionManager(this.templateTimeout, this::sequenceNumberTracker, this.optionListener);
        this.housekeepingFuture = executorService.scheduleAtFixedRate(this.sessionManager::doHousekeeping,
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
