package org.riptide;

import com.codahale.metrics.MetricRegistry;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.client.http.JestHttpClient;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
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
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public JestClient jestClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        // TODO fooker: trusts ALL certificates
        final var sslContext = new SSLContextBuilder()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();

        // TODO fooker: skips hostname checks
        final var hostnameVerifier = NoopHostnameVerifier.INSTANCE;

        final var sslSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);

        final var basicCredentialsProvider = new BasicCredentialsProvider();
        basicCredentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("elastic", "9y6Lw1o4TUpzQG3JYo0c"));

        final var factory = new JestClientFactory();
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
