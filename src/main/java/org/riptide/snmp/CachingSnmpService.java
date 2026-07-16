/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.riptide.utils.Tuple;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.time.Duration;

@Primary
@Service
@Slf4j
public class CachingSnmpService implements SnmpService {
    private final SnmpService delegate;
    private final SnmpCacheConfig cacheConfig;

    // Keyed by the poll address, NOT the SnmpEndpoint: SnmpEndpoint's equality spans the
    // mutable SnmpDefinition including credential references, so endpoint-keyed entries
    // would silently miss whenever credentials change representation.
    // expireAfterWrite (not Access) bounds staleness absolutely — the TTL backstop for
    // ifIndex reassignment after device reboots (RFC 2863).
    private final Cache<Tuple<InetSocketAddress, Integer>, Optional<IfInfo>> ifIndexCache;

    // Misses are cached separately with their own (shorter) TTL: every delegate miss is a
    // full table walk against the device, so an ifIndex that stays unresolvable must cost
    // one walk per TTL, not one per flow. Separate cache because Guava has no per-entry TTL.
    private final Cache<Tuple<InetSocketAddress, Integer>, Boolean> missCache;

    public CachingSnmpService(final SnmpService delegate, final SnmpCacheConfig cacheConfig) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cacheConfig = Objects.requireNonNull(cacheConfig);
        ifIndexCache = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofMillis(cacheConfig.getRetentionMs()))
                .build();
        missCache = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofMillis(cacheConfig.getNegativeRetentionMs()))
                // unlike ifIndexCache (bounded by real interfaces), misses are attacker-shaped:
                // a rogue exporter spraying distinct ifIndexes must not grow the heap
                .maximumSize(10_000)
                .build();
    }

    /** Config hot-reload hook: changed nodes may mean changed endpoints or credentials. */
    public void invalidateAll() {
        this.ifIndexCache.invalidateAll();
        this.missCache.invalidateAll();
    }

    @Override
    public Optional<IfInfo> getIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        final var key = Tuple.of(snmpEndpoint.getInetSocketAddress(), ifIndex);
        if (this.missCache.getIfPresent(key) != null) {
            return Optional.empty();
        }
        final Optional<IfInfo> ifInfo;
        try {
            ifInfo = ifIndexCache.get(key, () -> this.delegate.getIfInfo(snmpEndpoint, ifIndex));
        } catch (ExecutionException e) {
            // the delegate degrades all failures to Optional.empty and never throws
            throw new IllegalStateException(e);
        }
        if (ifInfo.isEmpty()) {
            ifIndexCache.invalidate(key);
            missCache.put(key, Boolean.TRUE);
            log.warn("Cannot determine interface info for ifIndex {} for endpoint {}", ifIndex, snmpEndpoint);
        }
        return ifInfo;
    }
}
