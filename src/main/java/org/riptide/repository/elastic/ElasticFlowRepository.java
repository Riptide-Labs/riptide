package org.riptide.repository.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import lombok.SneakyThrows;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.elastic.bulk.BulkException;
import org.riptide.repository.elastic.bulk.BulkExecutor;
import org.riptide.repository.elastic.doc.FlowDocument;
import org.riptide.repository.elastic.doc.FlowDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ElasticFlowRepository implements FlowRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticFlowRepository.class);

    private static final String INDEX_NAME = "netflow";

    private final ElasticsearchClient client;

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
        private final List<FlowDocument> documents = new ArrayList<>(ElasticFlowRepository.this.bulkSize);
        private final ReentrantLock lock = new ReentrantLock();
        private long lastPersist = 0;

        FlowBulk() {
        }
    }

    /**
     * Collect flow documents ready for persistence.
     */
    private final Map<Thread, FlowBulk> flowBulks = new ConcurrentHashMap<>();
    private java.util.Timer flushTimer;

    private final FlowDocumentMapper flowDocumentMapper;

    public ElasticFlowRepository(final MetricRegistry metricRegistry,
                                 final ElasticsearchClient jestClient,
                                 final IndexStrategy indexStrategy,
                                 final IndexSettings indexSettings,
                                 final FlowDocumentMapper flowDocumentMapper) {
        this.client = Objects.requireNonNull(jestClient);
        this.indexStrategy = Objects.requireNonNull(indexStrategy);
        this.indexSettings = Objects.requireNonNull(indexSettings);

        this.flowsPersistedMeter = metricRegistry.meter("flowsPersisted");
        this.logPersistingTimer = metricRegistry.timer("logPersisting");

        this.flowDocumentMapper = Objects.requireNonNull(flowDocumentMapper);
    }

    public ElasticFlowRepository(final MetricRegistry metricRegistry,
                                 final ElasticsearchClient jestClient,
                                 final IndexStrategy indexStrategy,
                                 final IndexSettings indexSettings,
                                 final int bulkSize,
                                 final int bulkFlushMs,
                                 final FlowDocumentMapper flowDocumentMapper) {
        this(metricRegistry, jestClient, indexStrategy, indexSettings, flowDocumentMapper);

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
                    for (final Map.Entry<Thread, ElasticFlowRepository.FlowBulk> entry : flowBulks.entrySet()) {
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
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        final FlowBulk flowBulk = this.flowBulks.computeIfAbsent(Thread.currentThread(), (thread) -> new FlowBulk());
        flowBulk.lock.lock();
        try {
            flows.stream()
                    .map(this.flowDocumentMapper::flowToDocument)
                    .forEach(flowBulk.documents::add);
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
        try (Timer.Context ctx = logPersistingTimer.time()) {
            final var bulkExecutor = new BulkExecutor<>(client, bulk, (documents) -> {
                final BulkRequest.Builder builder = new BulkRequest.Builder();
                for (final var flowDocument : documents) {
                    final String index = indexStrategy.getIndex(indexSettings, INDEX_NAME, Instant.ofEpochMilli(flowDocument.getTimestamp()));
                    builder.operations(op -> op
                            .index(idx -> idx
                                    .index(index)
                                    .document(flowDocument)));
                }
                return builder.build();
            }, bulkRetryCount);
            try {
                // the bulk request considers retries
                bulkExecutor.execute();
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

    @SneakyThrows
    public void stop() {
        stopTimer();
        for (final FlowBulk flowBulk : flowBulks.values()) {
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
