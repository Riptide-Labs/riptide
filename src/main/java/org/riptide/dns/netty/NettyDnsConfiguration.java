package org.riptide.dns.netty;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import org.riptide.config.enricher.HostnamesConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom;

public class NettyDnsConfiguration {
    @Bean
    DnsServerAddressStreamProvider nettyDnsServerAddressStreamProvider(HostnamesConfig config) {
        return new DefaultDnsServerAddressProvider(config);
    }

    @Bean
    DefaultDnsReverseCache nettyDnsResolverCache(MetricRegistry metricRegistry, HostnamesConfig config) {
        final var cache = new DefaultDnsReverseCache(config);
        initMetrics(metricRegistry, cache);
        return cache;
    }

    @Bean
    NettyDnsResolver nettyDnsResolver(MetricRegistry metricRegistry,
                                      HostnamesConfig config,
                                      DefaultDnsReverseCache cache,
                                      DnsServerAddressStreamProvider nameserverProvider) {
        return new NettyDnsResolver(metricRegistry, config, cache, nameserverProvider);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(value = "riptide.enricher.hostnames.breakerCircuitEnabled", havingValue = "true")
    CircuitBreakerDnsWrapper circuitBreakerDnsResolver(MetricRegistry metricRegistry, CircuitBreakerConfig config, NettyDnsResolver delegate) {
        return new CircuitBreakerDnsWrapper(metricRegistry, config, delegate);
    }

    @Bean
    @ConditionalOnProperty(value = "riptide.enricher.hostnames.breakerCircuitEnabled", havingValue = "true")
    CircuitBreakerConfig circuitBreakerConfig(HostnamesConfig config) {
        return custom()
                .failureRateThreshold(config.getBreakerFailureRateThreshold())
                .permittedNumberOfCallsInHalfOpenState(config.getBreakerRingBufferSizeInHalfOpenState())
                .slidingWindow(config.getBreakerRingBufferSizeInClosedState(), config.getBreakerRingBufferSizeInClosedState(), CircuitBreakerConfig.SlidingWindowType.COUNT_BASED, CircuitBreakerConfig.SlidingWindowSynchronizationStrategy.LOCK_FREE)
                .waitDurationInOpenState(Duration.ofSeconds(config.getBreakerWaitDurationInOpenState()))
                .recordExceptions(DnsNameResolverTimeoutException.class)
                .build();
    }

    static void initMetrics(MetricRegistry metricRegistry, DefaultDnsReverseCache cache) {
        metricRegistry.register("cacheSize", (Gauge<Long>) cache.delegate::estimatedSize);
        metricRegistry.register("cacheMaxSize", (Gauge<Long>) cache.config::getMaximumCacheSize);
        metricRegistry.register("cacheEvictionCount", (Gauge<Long>) () -> cache.delegate.stats().evictionCount());
        metricRegistry.register("cacheHits", (Gauge<Long>) () -> cache.delegate.stats().hitCount());
        metricRegistry.register("cacheMisses", (Gauge<Long>) () -> cache.delegate.stats().missCount());
    }

}
