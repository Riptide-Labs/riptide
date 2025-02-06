package org.riptide.dns.netty;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.dns.DefaultDnsCache;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.enricher.HostnamesConfig;
import org.riptide.dns.api.DnsResolver;
import org.springframework.beans.factory.DisposableBean;
import org.xbill.DNS.ReverseMap;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

// TODO MVR add circuit breaker
@Slf4j
public class NettyDnsResolver implements DnsResolver, DisposableBean {

    final DefaultDnsReverseCache reverseCache;
    final DnsServerAddressStreamProvider dnsNameserverProvider;
    final DefaultDnsCache nettyCache;
    final NioEventLoopGroup group;
    private final List<NettyDnsResolverWorker> workers;
    private final RoundRobinIterator<NettyDnsResolverWorker> iterator;
    private final Timer lookupTimer;

    public NettyDnsResolver(MetricRegistry metricRegistry, HostnamesConfig config, DefaultDnsReverseCache cache, DnsServerAddressStreamProvider dnsNameserverProvider) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(cache);
        Objects.requireNonNull(dnsNameserverProvider);
        Preconditions.checkArgument(config.getMaximumDnsResolverThreads() >= 1, "maximum resolver threads must be >= 1");
        Preconditions.checkArgument(config.getQueryTimeoutMillis() >= 1, "query timeout must be >= 1");
        this.reverseCache = Objects.requireNonNull(cache);
        this.nettyCache = new DefaultDnsCache();
        this.dnsNameserverProvider = dnsNameserverProvider;
        this.group = new NioEventLoopGroup(config.getMaximumDnsResolverThreads(), new ThreadFactoryBuilder()
                .setNameFormat("NettyDnsResolver-NIO-EventLoop-%d")
                .build());
        final var queryTimeout = config.getQueryTimeoutMillis();
        this.workers = IntStream.range(0, config.getMaximumDnsResolverThreads())
                .mapToObj(i -> new NettyDnsResolverWorker(this, queryTimeout))
                .toList();
        this.iterator = new RoundRobinIterator<>(workers);
        this.lookupTimer = metricRegistry.timer("reverseLookup");
    }

    @Override
    public CompletableFuture<Optional<String>> reverseLookup(InetAddress inetAddress) {
        if (inetAddress == null) return CompletableFuture.completedFuture(Optional.empty());
        final var reverseMapName = ReverseMap.fromAddress(inetAddress).toString();
        final var cachedEntry = reverseCache.getIfPresent(reverseMapName);
        if (cachedEntry != null && cachedEntry.isPresent()) {
            log.info("cache hit for: {}", reverseMapName);
            return CompletableFuture.completedFuture(Optional.of(cachedEntry.get().getCleanedHostname()));
        }
        return performReverseLookup(reverseMapName);
    }

    private CompletableFuture<Optional<String>> performReverseLookup(String reverseMapName) {
        try (var timer = lookupTimer.time()) {
            return iterator.next().performReverseLookup(reverseMapName);
        }
    }

    @Override
    public void destroy() throws Exception {
        group.shutdownGracefully();
        workers.forEach(NettyDnsResolverWorker::destroy);
        iterator.clear();
        reverseCache.invalidateAll();
    }

    static class RoundRobinIterator<T> implements Iterator<T> {
        private final List<T> delegate;
        private int currentIndex = 0;
        private boolean cleared = false;

        RoundRobinIterator(List<T> list) {
            this.delegate = new CopyOnWriteArrayList<>(list);
        }

        @Override
        public boolean hasNext() {
            return !cleared;
        }

        @Override
        public synchronized T next() {
            if (currentIndex == delegate.size()) {
                currentIndex = 0;
            }
            return delegate.get(currentIndex++);
        }

        @Override
        public void remove() {
            throw new RuntimeException("Removing is not implemented");
        }

        void clear() {
            this.cleared = true;
        }
    }
}
