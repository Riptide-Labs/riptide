package org.riptide.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.elastic")
@ConditionalOnProperty(name = "riptide.elastic.enabled", havingValue = "true", matchIfMissing = true)
@Data
public final class ElasticsearchConfig {
    public boolean enabled;
    public String url;
    public String username;
    public String password;
    public String indexPrefix = "riptide-netflow-";
    public Integer numberOfShards;
    public Integer numberOfReplicas;
    public Integer routingPartitionSize;
}
