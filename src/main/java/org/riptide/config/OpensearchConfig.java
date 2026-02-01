package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.opensearch")
public final class OpensearchConfig {
        public boolean enabled;
        public String host = "localhost";
        public int port = 9200;
        public String username;
        public String password;
        public String index = "riptide-netflow";
        public Integer numberOfShards = 1;
        public Integer numberOfReplicas = 1;
}
