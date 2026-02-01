package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.clickhouse")
public final class ClickhouseConfig {
        public String endpoint = "http://localhost:8123";

        public String username;
        public String password;

        public String database = "riptide";
}
