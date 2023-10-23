package org.riptide.flows.parser;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.Flow;
import org.riptide.flows.listeners.UdpParser;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.WithSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class UdpParserBase extends ParserBase implements UdpParser {
    public final static long HOUSEKEEPING_INTERVAL = 60000;

    private static final Logger LOG = LoggerFactory.getLogger(UdpParserBase.class);

    private final Meter packetsReceived;
    private final Counter parserErrors;

    private UdpSessionManager sessionManager;

    private ScheduledFuture<?> housekeepingFuture;
    private Duration templateTimeout = Duration.ofMinutes(30);

    public UdpParserBase(final Protocol protocol,
                         final String name,
                         final Consumer<WithSource<Flow>> dispatcher,
//                         final EventForwarder eventForwarder,
//                         final Identity identity,
                         final String location,
                         final DnsResolver dnsResolver,
                         final MetricRegistry metricRegistry) {
        super(protocol, name, dispatcher, /*eventForwarder, identity,*/ location, dnsResolver, metricRegistry);

        this.packetsReceived = metricRegistry.meter(MetricRegistry.name("parsers",  name, "packetsReceived"));
        this.parserErrors = metricRegistry.counter(MetricRegistry.name("parsers",  name, "parserErrors"));

        String sessionCountGauge = MetricRegistry.name("parsers",  name, "sessionCount");
        // Register only if it's not already there in the registry.
        if (!metricRegistry.getGauges().containsKey(sessionCountGauge)) {
            metricRegistry.register(sessionCountGauge, (Gauge<Integer>) () -> (this.sessionManager != null) ? this.sessionManager.count() : null);
        }
    }

    protected abstract RecordProvider parse(final Session session, final ByteBuf buffer) throws Exception;

    protected abstract UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress);

    public final CompletableFuture<?> parse(final ByteBuf buffer,
                                            final InetSocketAddress remoteAddress,
                                            final InetSocketAddress localAddress) throws Exception {
        this.packetsReceived.mark();

        final UdpSessionManager.SessionKey sessionKey = this.buildSessionKey(remoteAddress, localAddress);
        final Session session = this.sessionManager.getSession(sessionKey);

        try {
            final var parsed = this.parse(session, buffer);
            LOG.trace("Parsed packet: {}", parsed);

            return this.transmit(parsed, session, remoteAddress);
        } catch (Exception e) {
            this.sessionManager.drop(sessionKey);
            this.parserErrors.inc();
            throw e;
        }
    }

    @Override
    public void start(final ScheduledExecutorService executorService) {
        super.start(executorService);
        this.sessionManager = new UdpSessionManager(this.templateTimeout, this::sequenceNumberTracker);
        this.housekeepingFuture = executorService.scheduleAtFixedRate(this.sessionManager::doHousekeeping,
                HOUSEKEEPING_INTERVAL,
                HOUSEKEEPING_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        this.housekeepingFuture.cancel(false);
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
