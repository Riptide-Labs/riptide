package org.riptide.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.clickhouse")
@ConditionalOnProperty(name = "riptide.clickhouse.enabled", havingValue = "true")
@Data
public final class ClickhouseConfig {
        public boolean enabled;

        public String endpoint = "http://localhost:8123";

        public String username;
        public String password;

        public String database = "riptide";
}
