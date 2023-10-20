package org.riptide;

import com.codahale.metrics.MetricRegistry;
import io.searchbox.client.JestClient;
import io.searchbox.client.http.JestHttpClient;
import org.riptide.dns.api.DnsResolver;
import org.riptide.dns.netty.NettyDnsResolver;
import org.riptide.flows.repository.FlowRepository;
import org.riptide.flows.repository.elastic.ElasticFlowRepository;
import org.riptide.flows.repository.elastic.IndexSettings;
import org.riptide.flows.repository.elastic.IndexStrategy;
import org.riptide.flows.repository.elastic.InitializingElasticFlowRepository;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Set;

@SpringBootApplication
public class RiptideApplication {

    public static void main(final String... args) {
        final var application = new SpringApplication(RiptideApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }

    @Bean
    public DnsResolver dnsResolver(final MetricRegistry metricRegistry) {
        return new NettyDnsResolver(metricRegistry);
    }

    @Bean
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public JestClient jestClient() {
        final var client = new JestHttpClient();
        client.setServers(Set.of("http://localhost:9200"));

        return client;
    }

    @Bean
    public FlowRepository elasticFlowRepository(final JestClient jestClient,
                                                final MetricRegistry metricRegistry) {
        final var indexSettings = new IndexSettings();
        indexSettings.setIndexPrefix("riptide-");
        indexSettings.setNumberOfReplicas(1);
        indexSettings.setNumberOfShards(5);

        final var indexStrategy = IndexStrategy.DAILY;

        final var repository = new ElasticFlowRepository(metricRegistry, jestClient, indexStrategy, indexSettings);

        return new InitializingElasticFlowRepository(repository, jestClient, indexSettings);
    }

    @Bean
    public Map<String, FlowRepository> flowRepositories(final ListableBeanFactory beanFactory) {
        return beanFactory.getBeansOfType(FlowRepository.class);
    }
}
