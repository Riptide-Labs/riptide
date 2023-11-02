package org.riptide.snmp;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;


@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCache {
    public long retentionMs = 10 * 60 * 1000; // 10 minutes
    private LoadingCache<SnmpEndpoint, Map<Integer, String>> ifIndexCache = CacheBuilder.newBuilder()
            .expireAfterAccess(retentionMs, TimeUnit.MILLISECONDS)
            .build(
                    new CacheLoader<SnmpEndpoint, Map<Integer, String>>() {
                        public Map<Integer, String> load(final SnmpEndpoint snmpEndpoint) {
                            try {
                                return SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);
                            } catch (IOException e) {
                                return Collections.emptyMap();
                            }
                        }
                    });

    public SnmpCache() {
    }

    public Optional<String> getIfName(final SnmpEndpoint snmpEndpoint, final int ifIndex) throws ExecutionException {
        final Map<Integer, String> ifMap = ifIndexCache.get(snmpEndpoint);
        if (ifMap.containsKey(ifIndex)) {
            return Optional.of(ifMap.get(ifIndex));
        } else {
            ifIndexCache.invalidate(snmpEndpoint);
            return Optional.empty();
        }
    }
}
