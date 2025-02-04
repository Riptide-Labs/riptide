package org.riptide.configuration;

import org.riptide.config.ClickhouseConfig;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "riptide.clickhouse.enabled", havingValue = "true")
public class ClickhouseConfiguration {
    @Bean
    public ClickhouseRepository clickhouseRepository(final ClickhouseRepository.FlowMapper flowMapper, final ClickhouseConfig config) {
        return new ClickhouseRepository(flowMapper, config);
    }
}
