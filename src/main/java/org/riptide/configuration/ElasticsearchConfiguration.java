package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.riptide.config.ElasticsearchConfig;
import org.riptide.repository.Repository;
import org.riptide.repository.elastic.ElasticFlowRepository;
import org.riptide.repository.elastic.IndexSettings;
import org.riptide.repository.elastic.IndexStrategy;
import org.riptide.repository.elastic.InitializingElasticFlowRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
@ConditionalOnBean(ElasticsearchConfig.class)
public class ElasticsearchConfiguration {
    @Bean
    public JestClient jestClient(ElasticsearchConfig config) throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        // TODO fooker: Do not trusts ALL certificates
        final var sslContext = new SSLContextBuilder()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();

        // TODO fooker: Do not skips hostname checks
        final var hostnameVerifier = NoopHostnameVerifier.INSTANCE;

        final var sslSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);

        final var basicCredentialsProvider = new BasicCredentialsProvider();
        basicCredentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(config.username, config.password));

        final var factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig.Builder(config.url)
                .credentialsProvider(basicCredentialsProvider)
                .sslSocketFactory(sslSocketFactory)
                .multiThreaded(true)
                .defaultMaxTotalConnectionPerRoute(2)
                .maxTotalConnection(10)
                .build());
        return factory.getObject();
    }

    @Bean
    public Repository elasticFlowRepository(final ElasticsearchConfig config,
                                            final JestClient jestClient,
                                            final MetricRegistry metricRegistry) {
        final var indexSettings = new IndexSettings();
        indexSettings.setIndexPrefix(config.indexPrefix);
        indexSettings.setNumberOfReplicas(config.numberOfReplicas);
        indexSettings.setNumberOfShards(config.numberOfShards);
        indexSettings.setRoutingPartitionSize(config.routingPartitionSize);

        final var indexStrategy = IndexStrategy.DAILY;

        final var repository = new ElasticFlowRepository(metricRegistry, jestClient, indexStrategy, indexSettings);

        return new InitializingElasticFlowRepository(repository, jestClient, indexSettings);
    }

}
