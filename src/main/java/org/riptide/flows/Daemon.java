package org.riptide.flows;

import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.DaemonConfig;
import org.riptide.flows.listeners.UdpListener;
import org.riptide.flows.listeners.multi.DispatchableUdpParser;
import org.riptide.flows.listeners.multi.DispatchingUdpParser;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ipfix.IpfixUdpParser;
import org.riptide.flows.parser.netflow5.Netflow5UdpParser;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.pipeline.FlowException;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class Daemon implements ApplicationRunner {

    private final UdpListener listener;

    public Daemon(final Pipeline pipeline,
                  final MetricRegistry metricRegistry,
                  final ValueConversionService valueConversionService,
                  final DaemonConfig config) {

        final var location = "🤡 Clownworld 🤡";

        final BiConsumer<Source, Flow> dispatcher = (source, flow) -> {
            try {
                pipeline.process(source, Collections.singletonList(flow));
            } catch (final FlowException e) {
                // TODO fooker: real error handling
                throw new RuntimeException(e);
            }
        };

        final var netflow5UdpParser = new Netflow5UdpParser("default-netflow5",
                dispatcher,
                location,
                metricRegistry);

        final var netflow9UdpParser = new Netflow9UdpParser("default-netflow9",
                dispatcher,
                location,
                metricRegistry,
                valueConversionService);

        final var ipfixUdpParser = new IpfixUdpParser("default-ipfix",
                dispatcher,
                location,
                metricRegistry,
                valueConversionService);

        final Set<DispatchableUdpParser> parsers = Set.of(
                netflow5UdpParser,
                netflow9UdpParser,
                ipfixUdpParser
        );

        this.listener = new UdpListener("default",
                new DispatchingUdpParser("default", parsers),
                metricRegistry);
        log.info("Using port {} to listen for flows \\o/", config.port());
        this.listener.setPort(config.port());
    }

    @Override
    public void run(ApplicationArguments args) {
        this.listener.start();
    }

    @PreDestroy
    public void stop() {
        this.listener.stop();
    }
}
