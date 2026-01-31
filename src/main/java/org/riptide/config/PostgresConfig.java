package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.postgres")
public class PostgresConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private boolean persistFlows;
    private boolean persistBuckets;
}
