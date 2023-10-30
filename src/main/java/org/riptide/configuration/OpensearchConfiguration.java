package org.riptide.configuration;

import org.riptide.config.OpensearchConfig;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.opensearch.OpensearchRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(OpensearchConfig.class)
public class OpensearchConfiguration {
    @Bean
    public FlowRepository opensearchRepository(final OpensearchConfig config) {
        return new OpensearchRepository(config);
    }
}
