package org.riptide.config.enricher;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "riptide.enricher.hostnames")
@Data
public class HostnamesConfig {
    private boolean enable = true;

    private List<String> nameservers = List.of("127.0.0.1:53");
}
