package org.riptide.dns;

import lombok.extern.slf4j.Slf4j;
import org.riptide.dns.api.DnsResolver;
import org.riptide.dns.netty.NettyDnsConfiguration;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@Slf4j
@ConditionalOnProperty(name = "riptide.enricher.hostnames.enabled", havingValue = "true", matchIfMissing = true)
@Import(NettyDnsConfiguration.class)
public class HostnamesEnricher extends Enricher.Streaming {

    private final DnsResolver dnsResolver;

    public HostnamesEnricher(final DnsResolver dnsResolver) {
        this.dnsResolver = Objects.requireNonNull(dnsResolver);
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
