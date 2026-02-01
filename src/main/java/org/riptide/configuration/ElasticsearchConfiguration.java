package org.riptide.configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.codahale.metrics.MetricRegistry;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.riptide.config.ElasticsearchConfig;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.elastic.IndexSettings;
import org.riptide.repository.elastic.IndexStrategy;
import org.riptide.repository.elastic.InitializingElasticFlowRepository;
import org.riptide.repository.elastic.doc.FlowDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
@ConditionalOnProperty(name = "riptide.elastic.enabled", havingValue = "true")
public class ElasticsearchConfiguration {
    @Bean
    ElasticsearchClient jestClient(final ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    @Bean
    JsonpMapper jsonpMapper() {
        return new JacksonJsonpMapper();
    }

    @Bean
    RestClient restClient(final ElasticsearchConfig config) throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        // TODO fooker: Do not trusts ALL certificates
        final var sslContext = new SSLContextBuilder()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();

        // TODO fooker: Do not skips hostname checks
        final var hostnameVerifier = NoopHostnameVerifier.INSTANCE;

        final var basicCredentialsProvider = new BasicCredentialsProvider();
        basicCredentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(config.username, config.password));

        return RestClient
                .builder(HttpHost.create(config.url))
                .setHttpClientConfigCallback(client -> client
                        .setDefaultCredentialsProvider(basicCredentialsProvider)
                        .setMaxConnPerRoute(2)
                        .setMaxConnTotal(10)
                        .setSSLHostnameVerifier(hostnameVerifier)
                        .setSSLContext(sslContext))
                .build();
    }

    @Bean
    ElasticsearchTransport elasticsearchTransport(final RestClient restClient, final JsonpMapper jsonpMapper) {
        return new RestClientTransport(restClient, jsonpMapper);
    }

    @Bean
    FlowRepository elasticFlowRepository(final ElasticsearchConfig config,
                                                final ElasticsearchClient elasticsearchClient,
                                                final MetricRegistry metricRegistry,
                                                final FlowDocumentMapper flowDocumentMapper) {
        final var indexSettings = new IndexSettings();
        indexSettings.setIndexPrefix(config.indexPrefix);
        indexSettings.setNumberOfReplicas(config.numberOfReplicas);
        indexSettings.setNumberOfShards(config.numberOfShards);
        indexSettings.setRoutingPartitionSize(config.routingPartitionSize);

        final var indexStrategy = IndexStrategy.DAILY;

        return new InitializingElasticFlowRepository(metricRegistry, elasticsearchClient, indexStrategy, indexSettings, flowDocumentMapper, indexSettings);
    }

}
