package org.riptide.snmp;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "riptide.snmp.cache")
public class SnmpCacheConfig {
    public long retentionMs;
}
