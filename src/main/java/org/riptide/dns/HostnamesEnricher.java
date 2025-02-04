package org.riptide.dns;

import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.enricher.HostnamesConfig;
import org.riptide.dns.netty.NettyDnsResolver;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@Slf4j
@ConditionalOnProperty(name = "riptide.enricher.hostnames.enabled", havingValue = "true", matchIfMissing = true)
public class HostnamesEnricher extends Enricher.Streaming {

    private final NettyDnsResolver dnsResolver;

    public HostnamesEnricher(final HostnamesConfig config,
                             final MetricRegistry metricRegistry) {
        this.dnsResolver = new NettyDnsResolver(metricRegistry);
        this.dnsResolver.setNameservers(String.join(",", config.getNameservers()));
        this.dnsResolver.init();
    }

    @Override
    protected Stream<CompletableFuture<Void>> enrich(Source source, EnrichedFlow flow) {
        return Stream.of(
                this.dnsResolver.reverseLookup(flow.getSrcAddr()).handle(apply(flow::setSrcAddrHostname)),
                this.dnsResolver.reverseLookup(flow.getDstAddr()).handle(apply(flow::setDstAddrHostname)),
                this.dnsResolver.reverseLookup(flow.getNextHop()).handle(apply(flow::setNextHopHostname)));
    }

    /**
     * Returns a consumer for optionals that calls the provided consumer if the consumed optional is present.
     **/
    private static <T> BiFunction<Optional<T>, Throwable, Void> apply(final Consumer<T> consumer) {
        return (optional, ex) -> {
            if (ex != null) {
                log.trace("Reverse lookup failed", ex);
                return null;
            }

            optional.ifPresent(consumer);

            return null;
        };
    }
}
