package org.riptide.config.enricher;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.enricher.hostnames")
@Data
public class HostnameConfig {
    public boolean enable = true;
}
