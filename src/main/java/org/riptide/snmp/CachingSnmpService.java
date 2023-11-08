package org.riptide.snmp;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.riptide.utils.Tuple;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Primary
@Service
@Slf4j
public class CachingSnmpService implements SnmpService {
    private final SnmpService delegate;
    private final SnmpCacheConfig cacheConfig;
    private final LoadingCache<Tuple<SnmpEndpoint, Integer>, Optional<String>> ifIndexCache;

    public CachingSnmpService(final SnmpService delegate, final SnmpCacheConfig cacheConfig) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cacheConfig = Objects.requireNonNull(cacheConfig);
        ifIndexCache = CacheBuilder.newBuilder()
                .expireAfterAccess(cacheConfig.retentionMs, TimeUnit.MILLISECONDS)
                .build(new CacheLoader<>() {
                    public Optional<String> load(final Tuple<SnmpEndpoint, Integer> key) {
                        return delegate.getIfName(key.first(), key.second());
                    }
                });
    }

    @Override
    public Optional<String> getIfName(SnmpEndpoint snmpEndpoint, int ifIndex) {
        final var key = Tuple.of(snmpEndpoint, ifIndex);
        final Optional<String> ifNameOptional = ifIndexCache.getUnchecked(key);
        if (ifNameOptional.isEmpty()) {
            ifIndexCache.invalidate(key);
            log.warn("Cannot determine ifName for ifIndex {} for endpoint {}", ifIndex, snmpEndpoint);
        }
        return ifNameOptional;
    }
}
