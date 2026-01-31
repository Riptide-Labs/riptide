package org.riptide.repository.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.codahale.metrics.MetricRegistry;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.elastic.doc.FlowDocumentMapper;
import org.riptide.repository.elastic.template.DefaultTemplateInitializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This {@link FlowRepository} wrapper will ensure that the repository has
 * been initialized before any *write* calls are made to the given delegate.
 */
public class InitializingElasticFlowRepository extends ElasticFlowRepository {

    private final List<DefaultTemplateInitializer> initializers;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public InitializingElasticFlowRepository(
            final MetricRegistry metricRegistry,
            final ElasticsearchClient jestClient,
            final IndexStrategy indexStrategy,
            final IndexSettings indexSettings,
            final FlowDocumentMapper flowDocumentMapper,
            final IndexSettings rawIndexSettings) {
        this(metricRegistry,
                jestClient,
                indexStrategy,
                indexSettings,
                flowDocumentMapper,
                new RawIndexInitializer(jestClient, rawIndexSettings));
    }

    private InitializingElasticFlowRepository(
            final MetricRegistry metricRegistry,
            final ElasticsearchClient jestClient,
            final IndexStrategy indexStrategy,
            final IndexSettings indexSettings,
            final FlowDocumentMapper flowDocumentMapper,
            final DefaultTemplateInitializer... initializers) {
        super(metricRegistry,
                jestClient,
                indexStrategy,
                indexSettings,
                flowDocumentMapper);

        this.initializers = Arrays.asList(initializers);
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        try {
            ensureInitialized();
        } catch (final Exception ex) {
            throw new UnrecoverableFlowException(ex.getMessage(), ex);
        }

        super.persist(flows);
    }

    private void ensureInitialized() {
        if (initialized.get()) {
            return;
        }
        for (DefaultTemplateInitializer initializer : initializers) {
            if (!initializer.isInitialized()) {
                initializer.initialize();
            }
        }
        initialized.set(true);
    }

}
