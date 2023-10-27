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
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@SpringBootApplication
public class RiptideApplication {

    public static void main(final String... args) {
        final var application = new SpringApplication(RiptideApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }
}
