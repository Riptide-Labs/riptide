package org.riptide.repository.opensearch;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.riptide.config.OpensearchConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class OpensearchRepository implements FlowRepository {

    private static final Logger LOG = LoggerFactory.getLogger(OpensearchRepository.class);

    public static final JacksonJsonpMapper JSON_MAPPER = new JacksonJsonpMapper();

    private final String index;
    private final OpenSearchClient client;

    public OpensearchRepository(final OpensearchConfig config) {
        this.index = Objects.requireNonNull(config.index);

        final var host = new HttpHost("https", config.host, config.port);

        final var credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope(host),
                new UsernamePasswordCredentials(config.username, config.password.toCharArray()));

        final var connectionManager = PoolingAsyncClientConnectionManagerBuilder
                .create()
                .build();

        final var transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(JSON_MAPPER)
                .setHttpClientConfigCallback(client -> client
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setConnectionManager(connectionManager))
                .build();

        this.client = new OpenSearchClient(transport);
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        this.ensureTemplate();

        final var ops = flows.stream()
                .map(flow -> new BulkOperation.Builder().index(index -> index
                                .index(OpensearchRepository.this.index)
                                .document(flow)
                                .requireAlias(true))
                        .build())
                .toList();

        final var response = this.client.bulk(bulk -> bulk
                .index(index)
                .requireAlias(true)
                .operations(ops)
                .refresh(Refresh.WaitFor));

        if (response.errors()) {
            for (final var item : response.items()) {
                if (item.error() != null) {
                    LOG.error("Bulk index failed: {}", item.error());
                }
            }
        }
    }

    private void ensureTemplate() throws IOException {
        if (this.client.indices().existsTemplate(builder -> builder.name(index)).value()) {
            return;
        }

        final var mappings = JSON_MAPPER.deserialize(
                JSON_MAPPER.jsonProvider().createParser(this.getClass().getResourceAsStream("opensearch/index-mappings.json")),
                TypeMapping.class);

        this.client.indices().putIndexTemplate(indexTemplate -> indexTemplate
                .name(index)
                .indexPatterns(String.format("%s-*", index))
                .template(template -> template
                        .aliases(index, alias -> alias)
                        .settings(indexSettings -> indexSettings
                                .numberOfReplicas(Integer.valueOf("1"))
                                .numberOfShards(Integer.valueOf("1")))
                        .mappings(mappings)
                ));
    }
}
