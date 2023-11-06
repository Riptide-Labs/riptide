package org.riptide.snmp;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;


@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCache {
    private static final Logger LOG = LoggerFactory.getLogger(SnmpCache.class);

    public long retentionMs = 10 * 60 * 1000; // 10 minutes
    private LoadingCache<SnmpDefinition.SnmpEndpoint, Map<Integer, String>> ifIndexCache = CacheBuilder.newBuilder()
            .expireAfterAccess(retentionMs, TimeUnit.MILLISECONDS)
            .build(
                    new CacheLoader<SnmpDefinition.SnmpEndpoint, Map<Integer, String>>() {
                        public Map<Integer, String> load(final SnmpDefinition.SnmpEndpoint snmpEndpoint) {
                            try {
                                return SnmpUtils.getSnmpInterfaceMap(snmpEndpoint);
                            } catch (IOException e) {
                                return Collections.emptyMap();
                            }
                        }
                    });

    public SnmpCache() {
    }

    public Optional<String> getIfName(final SnmpDefinition.SnmpEndpoint snmpEndpoint, final int ifIndex) throws ExecutionException {
        final Optional<String> ifNameOptional = Optional.ofNullable(ifIndexCache.get(snmpEndpoint).get(ifIndex));

        if (ifNameOptional.isEmpty()) {
            ifIndexCache.invalidate(snmpEndpoint);
            LOG.warn("Cannot determine ifName for ifIndex {}for endpoint {}", ifIndex, snmpEndpoint);
        }

        return ifNameOptional;
    }
}
