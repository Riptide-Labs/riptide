package org.riptide.flows.repository.elastic;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.searchbox.client.JestClient;
import io.searchbox.core.Bulk;
import io.searchbox.core.Index;
import org.riptide.flows.pipeline.EnrichedFlow;
import org.riptide.flows.pipeline.FlowException;
import org.riptide.flows.repository.FlowRepository;
import org.riptide.flows.repository.elastic.bulk.BulkException;
import org.riptide.flows.repository.elastic.bulk.BulkRequest;
import org.riptide.flows.repository.elastic.bulk.BulkWrapper;
import org.riptide.flows.repository.elastic.doc.FlowDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;

public class ElasticFlowRepository implements FlowRepository {

    public static final String TRACER_FLOW_MODULE = "ElasticFlow";

    private static final Logger LOG = LoggerFactory.getLogger(ElasticFlowRepository.class);

    private static final String INDEX_NAME = "netflow";

    private final JestClient client;

    private final IndexStrategy indexStrategy;

    /**
     * Flows/second throughput
     */
    private final Meter flowsPersistedMeter;

    /**
     * Time taken to persist the flows in a log
     */
    private final Timer logPersistingTimer;

    private final IndexSettings indexSettings;

    private int bulkSize = 1000;
    private int bulkRetryCount = 5;
    private int bulkFlushMs = 500;

    private class FlowBulk {
        private List<FlowDocument> documents = Lists.newArrayListWithCapacity(ElasticFlowRepository.this.bulkSize);
        private ReentrantLock lock = new ReentrantLock();
        private long lastPersist = 0;

        public FlowBulk() {
        }
    }

    /**
     * Collect flow documents ready for persistence.
     */
    private final Map<Thread, FlowBulk> flowBulks = Maps.newConcurrentMap();
    private java.util.Timer flushTimer;

    public ElasticFlowRepository(final MetricRegistry metricRegistry,
                                 final JestClient jestClient,
                                 final IndexStrategy indexStrategy,
                                 final IndexSettings indexSettings) {
        this.client = Objects.requireNonNull(jestClient);
        this.indexStrategy = Objects.requireNonNull(indexStrategy);
        this.indexSettings = Objects.requireNonNull(indexSettings);

        this.flowsPersistedMeter = metricRegistry.meter("flowsPersisted");
        this.logPersistingTimer = metricRegistry.timer("logPersisting");
    }

    public ElasticFlowRepository(final MetricRegistry metricRegistry,
                                 final JestClient jestClient,
                                 final IndexStrategy indexStrategy,
                                 final IndexSettings indexSettings,
                                 final int bulkSize,
                                 final int bulkFlushMs) {
        this(metricRegistry, jestClient, indexStrategy, indexSettings);

        this.bulkSize = bulkSize;
        this.bulkFlushMs = bulkFlushMs;
    }

    private void startTimer() {
        if (flushTimer != null) {
            return;
        }

        if (bulkFlushMs > 0) {
            int delay = Math.max(1, bulkFlushMs / 2);
            flushTimer = new java.util.Timer("ElasticFlowRepositoryFlush");
            flushTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    final long currentTimeMillis = System.currentTimeMillis();
                    for(final Map.Entry<Thread, ElasticFlowRepository.FlowBulk> entry : flowBulks.entrySet()) {
                        final ElasticFlowRepository.FlowBulk flowBulk = entry.getValue();
                        if (currentTimeMillis - flowBulk.lastPersist > bulkFlushMs) {
                            if (flowBulk.lock.tryLock()) {
                                try {
                                    if (!flowBulk.documents.isEmpty()) {
                                        try {
                                            persistBulk(flowBulk.documents);
                                            flowBulk.lastPersist = currentTimeMillis;
                                        } catch (Throwable t) {
                                            LOG.error("An error occurred while flushing one or more bulks in ElasticFlowRepository.", t);
                                        }
                                    }
                                } finally {
                                    flowBulk.lock.unlock();
                                }
                            }
                        }
                    }
                }
            }, delay, delay);
        } else {
            flushTimer = null;
        }
    }

    private void stopTimer() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
    }

    @Override
    public void persist(final Collection<EnrichedFlow> flows) throws FlowException {
        final FlowBulk flowBulk = this.flowBulks.computeIfAbsent(Thread.currentThread(), (thread) -> new FlowBulk());
        flowBulk.lock.lock();
        try {
            flows.stream().map(FlowDocument::from).forEach(flowBulk.documents::add);
            if (flowBulk.documents.size() >= this.bulkSize) {
                this.persistBulk(flowBulk.documents);
                flowBulk.lastPersist = System.currentTimeMillis();
            }
        } finally {
            flowBulk.lock.unlock();
        }
    }

    private void persistBulk(final List<FlowDocument> bulk) throws FlowException {
        LOG.debug("Persisting {} flow documents.", bulk.size());
        try (final Timer.Context ctx = logPersistingTimer.time()) {
            final BulkRequest<FlowDocument> bulkRequest = new BulkRequest<>(client, bulk, (documents) -> {
                final Bulk.Builder bulkBuilder = new Bulk.Builder();
                for (FlowDocument flowDocument : documents) {
                    final String index = indexStrategy.getIndex(indexSettings, INDEX_NAME, Instant.ofEpochMilli(flowDocument.getTimestamp()));
                    final Index.Builder indexBuilder = new Index.Builder(flowDocument)
                            .index(index);
                    bulkBuilder.addAction(indexBuilder.build());
                }
                return new BulkWrapper(bulkBuilder);
            }, bulkRetryCount);
            try {
                // the bulk request considers retries
                bulkRequest.execute();
            } catch (BulkException ex) {
                if (ex.getBulkResult() != null) {
                    throw new PersistenceException(ex.getMessage(), ex.getBulkResult().getFailedItems());
                } else {
                    throw new PersistenceException(ex.getMessage(), Collections.emptyList());
                }
            } catch (IOException ex) {
                LOG.error("An error occurred while executing the given request: {}", ex.getMessage(), ex);
                throw new FlowException(ex.getMessage(), ex);
            }
            flowsPersistedMeter.mark(bulk.size());

            bulk.clear();
        }
    }

    public void start() {
        startTimer();
    }

    public void stop() throws FlowException {
        stopTimer();
        for(final FlowBulk flowBulk : flowBulks.values()) {
            persistBulk(flowBulk.documents);
        }
    }

    public int getBulkSize() {
        return this.bulkSize;
    }

    public void setBulkSize(final int bulkSize) {
        this.bulkSize = bulkSize;
    }

    public int getBulkRetryCount() {
        return bulkRetryCount;
    }

    public void setBulkRetryCount(int bulkRetryCount) {
        this.bulkRetryCount = bulkRetryCount;
    }

    public int getBulkFlushMs() {
        return bulkFlushMs;
    }

    public void setBulkFlushMs(final int bulkFlushMs) {
        this.bulkFlushMs = bulkFlushMs;

        stopTimer();
        startTimer();
    }
}
