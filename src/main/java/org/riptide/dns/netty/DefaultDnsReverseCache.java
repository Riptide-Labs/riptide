package org.riptide.dns.netty;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.riptide.config.enricher.HostnamesConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class DefaultDnsReverseCache {

    public final Cache<String, Optional<DnsReverseCacheEntry>> delegate;
    public final HostnamesConfig config;

    public DefaultDnsReverseCache(HostnamesConfig config) {
        Objects.requireNonNull(config);
        final var cacheBuilder = Caffeine.newBuilder().expireAfter(new Expiry<String, Optional<DnsReverseCacheEntry>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<DnsReverseCacheEntry> value, long currentTime) {
                        return value.map(it -> {
                            return Duration.ofSeconds(it.getRecord().timeToLive()).toNanos(); // use the TTL from the DNS record
                        }).orElse(Duration.ofSeconds(config.getDefaultCacheTtl()).toNanos());
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<DnsReverseCacheEntry> value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime); // we use the same values for update as for creation
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<DnsReverseCacheEntry> value, long currentTime, long currentDuration) {
                        return currentDuration; // we don't want to expire after read
                    }
                })
                .recordStats();
        if (config.getMaximumCacheSize() != null && config.getMaximumCacheSize() > 0) {
            cacheBuilder.maximumSize(config.getMaximumCacheSize());
        }
        this.config = config;
        this.delegate = cacheBuilder.build();
    }

    public Optional<DnsReverseCacheEntry> getIfPresent(String reverseMapName) {
        return delegate.getIfPresent(reverseMapName);
    }

    public void put(String key, Optional<DnsReverseCacheEntry> entry) {
        delegate.put(key, entry);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }
}
