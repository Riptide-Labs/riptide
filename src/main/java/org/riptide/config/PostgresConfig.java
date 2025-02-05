package org.riptide.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.postgres")
@ConditionalOnProperty(name = "riptide.postgres.enabled", havingValue = "true")
public class PostgresConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private boolean persistFlows;
    private boolean persistBuckets;
}
