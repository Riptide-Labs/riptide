package org.riptide.flows;

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
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.pipeline.FlowException;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class Daemon implements ApplicationRunner {

    private final List<Listener> listeners;

    public Daemon(final Pipeline pipeline,
                  final MetricRegistry metricRegistry,
                  @Qualifier("ipfixValueConversionService") final ValueConversionService ipfixValueConversionService,
                  @Qualifier("netflow9ValueConversionService") final ValueConversionService netflow9ValueConversionService,
                  final DaemonConfig config) {

        final var location = config.getLocation();

        final BiConsumer<Source, Flow> dispatcher = (source, flow) -> {
            try {
                pipeline.process(source, Collections.singletonList(flow));
            } catch (final FlowException e) {
                // TODO fooker: real error handling
                throw new RuntimeException(e);
            }
        };

        this.listeners = config.getReceivers().entrySet().stream()
                .map(e -> e.getValue().accept(new ReceiverConfig.Cases<Listener>() {
                    @Override
                    public Listener match(final ReceiverConfig.Neflow5Config config) {
                        final var parser = new Netflow5UdpParser(e.getKey(), dispatcher, location, metricRegistry);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }

                    @Override
                    public Listener match(final ReceiverConfig.Neflow9Config config) {
                        final var parser = new Netflow9UdpParser(e.getKey(), dispatcher, location, metricRegistry, netflow9ValueConversionService)
                                .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }

                    @Override
                    public Listener match(final ReceiverConfig.IpfixConfig config) {
                        return switch (config.getTransport()) {
                            case UDP -> {
                                final var parser = new IpfixUdpParser(e.getKey(), dispatcher, location, metricRegistry, ipfixValueConversionService)
                                        .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                        .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());

                                yield new UdpListener(e.getKey(), parser, metricRegistry)
                                        .withPort(config.getPort())
                                        .withHost(config.getHost());
                            }
                            case TCP -> {
                                final var parser = new IpfixTcpParser(e.getKey(), dispatcher, location, metricRegistry, ipfixValueConversionService)
                                        .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                        .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                        .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback());

                                yield new TcpListener(e.getKey(), parser, metricRegistry)
                                        .withPort(config.getPort())
                                        .withHost(config.getHost());
                            }
                        };
                    }

                    @Override
                    public Listener match(final ReceiverConfig.MultiConfig config) {
                        final var parsers = new HashSet<DispatchableUdpParser>();

                        if (config.isNetflow5()) {
                            parsers.add(new Netflow5UdpParser(e.getKey() + ":netflow5", dispatcher, location, metricRegistry));
                        }

                        if (config.isNetflow9()) {
                            parsers.add(new Netflow9UdpParser(e.getKey() + ":netflow9", dispatcher, location, metricRegistry, netflow9ValueConversionService)
                                    .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                    .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback()));
                        }

                        if (config.isIpfix()) {
                            parsers.add(new IpfixUdpParser(e.getKey() + ":ipfix", dispatcher, location, metricRegistry, ipfixValueConversionService)
                                    .withFlowActiveTimeoutFallback(config.getFlowActiveTimeoutFallback())
                                    .withFlowInactiveTimeoutFallback(config.getFlowInactiveTimeoutFallback())
                                    .withFlowSamplingIntervalFallback(config.getFlowSamplingIntervalFallback()));
                        }

                        final var parser = new DispatchingUdpParser(e.getKey(), parsers);

                        return new UdpListener(e.getKey(), parser, metricRegistry)
                                .withPort(config.getPort())
                                .withHost(config.getHost());
                    }
                })).toList();

        log.info("Listening for flows with {} receivers \\o/", this.listeners.size());
    }

    @Override
    public void run(ApplicationArguments args) {
        this.listeners.forEach(Listener::start);
    }

    @PreDestroy
    public void stop() {
        this.listeners.forEach(Listener::stop);
    }
}
