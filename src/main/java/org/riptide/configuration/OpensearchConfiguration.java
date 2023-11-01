package org.riptide.configuration;

import org.riptide.config.OpensearchConfig;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.opensearch.OpensearchRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "riptide.opensearch.enabled", havingValue = "true")
@EnableAutoConfiguration(exclude = {ElasticsearchClientAutoConfiguration.class, ElasticsearchRestClientAutoConfiguration.class}) // Disable elastic client auto configuration
public class OpensearchConfiguration {
    @Bean
    public FlowRepository opensearchRepository(final OpensearchConfig config) {
        return new OpensearchRepository(config);
    }
}
