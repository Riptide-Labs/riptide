package org.riptide.config.enricher;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.enricher.classification")
@Data
public class ClassificationConfig {
    public boolean enabled = true;
}
