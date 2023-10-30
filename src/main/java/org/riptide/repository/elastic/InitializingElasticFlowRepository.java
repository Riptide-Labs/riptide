package org.riptide.repository.elastic;

import io.searchbox.client.JestClient;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.elastic.template.DefaultTemplateInitializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This {@link FlowRepository} wrapper will ensure that the repository has
 * been initialized before any *write* calls are made to the given delegate.
 */
public class InitializingElasticFlowRepository implements FlowRepository {

    private final List<DefaultTemplateInitializer> initializers;
    private final FlowRepository delegate;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public InitializingElasticFlowRepository(final FlowRepository delegate,
                                             final JestClient client,
                                             final IndexSettings rawIndexSettings/*,
                                      final IndexSettings aggIndexSettings*/) {
        this(delegate, new RawIndexInitializer(client, rawIndexSettings)/*, new AggregateIndexInitializer(bundleContext, client, aggIndexSettings)*/);
    }

    private InitializingElasticFlowRepository(final FlowRepository delegate, final DefaultTemplateInitializer... initializers) {
        this.delegate = Objects.requireNonNull(delegate);
        this.initializers = Arrays.asList(initializers);
    }

    @Override
    public void persist(final Collection<EnrichedFlow> flows) throws FlowException, IOException {
        try {
            ensureInitialized();
        } catch (final Exception ex) {
            throw new UnrecoverableFlowException(ex.getMessage(), ex);
        }

        delegate.persist(flows);
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
