package org.riptide;

import com.codahale.metrics.MetricRegistry;
import com.moandjiezana.toml.Toml;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.internal.AsyncReloadingClassificationEngine;
import org.riptide.classification.internal.DefaultClassificationEngine;
import org.riptide.classification.internal.TimingClassificationEngine;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.config.Config;
import org.riptide.repository.Repository;
import org.riptide.repository.elastic.ElasticFlowRepository;
import org.riptide.repository.elastic.IndexSettings;
import org.riptide.repository.elastic.IndexStrategy;
import org.riptide.repository.elastic.InitializingElasticFlowRepository;
import org.riptide.repository.opensearch.OpensearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Configuration
public class RiptideConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RiptideApplication.class);

    @Bean
    public Config config() throws IOException {
        return new Toml()
                .read(new File("riptide.conf"))
                .to(Config.class);
    }

    @Bean
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public JestClient jestClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        // TODO fooker: Do not trusts ALL certificates
        final var sslContext = new SSLContextBuilder()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();

        // TODO fooker: Do not skips hostname checks
        final var hostnameVerifier = NoopHostnameVerifier.INSTANCE;

        final var sslSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);

        final var basicCredentialsProvider = new BasicCredentialsProvider();
        // TODO MVR make configurable
        basicCredentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("elastic", "9y6Lw1o4TUpzQG3JYo0c"));

        final var factory = new JestClientFactory();
        // TODO MVR make configurable
        factory.setHttpClientConfig(new HttpClientConfig.Builder("https://localhost:9200")
                .credentialsProvider(basicCredentialsProvider)
                .sslSocketFactory(sslSocketFactory)
                .multiThreaded(true)
                .defaultMaxTotalConnectionPerRoute(2)
                .maxTotalConnection(10)
                .build());
        return factory.getObject();
    }

    @Bean
    public Repository elasticFlowRepository(final Config config,
                                            final JestClient jestClient,
                                            final MetricRegistry metricRegistry) {
        if (config.elasticsearch == null) {
            return null;
        }

        final var indexSettings = new IndexSettings();
        indexSettings.setIndexPrefix(config.elasticsearch.indexPrefix);
        indexSettings.setNumberOfReplicas(config.elasticsearch.numberOfReplicas);
        indexSettings.setNumberOfShards(config.elasticsearch.numberOfShards);
        indexSettings.setRoutingPartitionSize(config.elasticsearch.routingPartitionSize);

        final var indexStrategy = IndexStrategy.DAILY;

        final var repository = new ElasticFlowRepository(metricRegistry, jestClient, indexStrategy, indexSettings);

        return new InitializingElasticFlowRepository(repository, jestClient, indexSettings);
    }

    @Bean
    public Repository opensearchRepository(final Config config) {
        if (config.opensearchConfig == null) {
            return null;
        }
        return new OpensearchRepository(config.opensearchConfig);
    }

    // TODO MVR using Map<String, Repository> seems weird
    @Bean
    public Map<String, Repository> flowRepositories(final ListableBeanFactory beanFactory) {
        final var repositories = beanFactory.getBeansOfType(Repository.class);
        if (repositories.isEmpty()) {
            LOG.error("No flow persistence repository configured");
        }
        return repositories;
    }

    @Bean
    public ClassificationRuleProvider classificationRuleProvider() throws IOException {
        final var rules = CsvImporter.parse(RiptideApplication.class.getResourceAsStream("/classification-rules.csv"), true);
        return ClassificationRuleProvider.forList(rules);
    }

    @Bean
    public ClassificationEngine classificationEngine(final ClassificationRuleProvider classificationRuleProvider,
                                                     final MetricRegistry metricRegistry) throws InterruptedException {
        final var engine = new DefaultClassificationEngine(classificationRuleProvider, false);
        final var timingEngine = new TimingClassificationEngine(metricRegistry, engine);
        final var reloadingEngine = new AsyncReloadingClassificationEngine(timingEngine);
        return reloadingEngine;
    }
}
