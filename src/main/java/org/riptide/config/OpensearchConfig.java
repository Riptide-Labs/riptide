package org.riptide.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.opensearch")
@ConditionalOnProperty(name="riptide.opensearch.enabled", havingValue = "true")
@Data
public final class OpensearchConfig {
        public String host = "localhost";
        public int port = 9200;
        public String username;
        public String password;
        public String index = "riptide-netflow";
        public Integer numberOfShards = 1;
        public Integer numberOfReplicas = 1;
}
