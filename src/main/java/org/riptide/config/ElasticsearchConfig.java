package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.elastic")
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
