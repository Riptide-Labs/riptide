package org.riptide.config.enricher;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "riptide.enricher.hostnames")
@Data
public class HostnamesConfig {
    private boolean enabled = true;
    private long defaultCacheTtl = Duration.ofMinutes(1).toSeconds();
    private Long maximumCacheSize;
    private long queryTimeoutMillis = Duration.ofSeconds(5).toMillis();
    private int resolverThreads = 5;
    private List<String> nameservers = new ArrayList<>();
    private int maximumDnsResolverThreads = Runtime.getRuntime().availableProcessors() / 2;

    private boolean breakerCircuitEnabled = false;
    private int breakerWaitDurationInOpenState = 20;
    private int breakerRingBufferSizeInHalfOpenState = 20;
    private int breakerFailureRateThreshold = 100;
    private int breakerRingBufferSizeInClosedState = 100;
}
