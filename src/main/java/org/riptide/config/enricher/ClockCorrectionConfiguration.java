package org.riptide.config.enricher;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "riptide.enricher.clock-correction")
@Data
public class ClockCorrectionConfiguration {
    public boolean enabled = true;
    public long skewThresholdMs = 0;
}
