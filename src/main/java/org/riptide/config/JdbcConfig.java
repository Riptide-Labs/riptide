package org.riptide.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.jdbc")
@ConditionalOnProperty(name = "riptide.jdbc.enabled", havingValue = "true")
@Data
public final class JdbcConfig {
    private boolean enabled;
}
