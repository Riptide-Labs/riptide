package org.riptide.dns.netty;

import com.codahale.metrics.MetricRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.riptide.dns.api.DnsResolver;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class CircuitBreakerDnsWrapper implements DnsResolver {

    private final CircuitBreaker circuitBreaker;
    private final DnsResolver delegate;

    public CircuitBreakerDnsWrapper(MetricRegistry metricRegistry,
                                    CircuitBreakerConfig config,
                                    DnsResolver delegate) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(delegate);
        this.delegate = delegate;
        this.circuitBreaker = CircuitBreaker.of("dnsReverseResolver", config);
        final var lookupsSuccessful = metricRegistry.meter(MetricRegistry.name("circuitBreaker", "lookupSuccessful"));
        final var lookupsFailed = metricRegistry.meter(MetricRegistry.name("circuitBreaker", "lookupsFailed"));
        final var lookupsRejected = metricRegistry.meter(MetricRegistry.name("circuitBreaker", "lookupsRejected"));
        circuitBreaker.getEventPublisher()
                .onStateTransition(state -> log.debug("Changed state from [{}] to [{}]",
                        state.getStateTransition().getFromState(),
                        state.getStateTransition().getToState()))
                .onSuccess(e -> lookupsSuccessful.mark())
                .onError(e -> lookupsFailed.mark())
                .onCallNotPermitted(e -> lookupsRejected.mark());
        circuitBreaker.transitionToClosedState();
    }

    @Override
    public CompletableFuture<Optional<String>> reverseLookup(InetAddress inetAddress) {
        return circuitBreaker.executeCompletionStage(() -> delegate.reverseLookup(inetAddress)).toCompletableFuture();
    }
}
