package org.riptide.flows.dispatcher;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class AsyncDispatcherImpl<S, T> implements AsyncDispatcher<S> {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncDispatcherImpl.class);

    private final String id;
    private final SyncDispatcher<S> syncDispatcher;
    private final AsyncPolicy asyncPolicy;
    private final Counter droppedCounter;

    private final Map<String, CompletableFuture<DispatchStatus>> futureMap = new ConcurrentHashMap<>();
    private final AtomicResultQueue<S> atomicResultQueue;
    private final AtomicLong missedFutures = new AtomicLong(0);
    private final AtomicInteger activeDispatchers = new AtomicInteger(0);
    
    private final ExecutorService executor;

    public AsyncDispatcherImpl(final String id,
                               final AsyncPolicy asyncPolicy,
                               final SyncDispatcher<S> syncDispatcher,
                               final MetricRegistry metricRegistry) {
        this.id = Objects.requireNonNull(id);

        this.syncDispatcher = Objects.requireNonNull(syncDispatcher);
        this.asyncPolicy = Objects.requireNonNull(asyncPolicy);

        final DispatchQueue<S> dispatchQueue = new DefaultQueue<>(this.asyncPolicy.getQueueSize());

//        if (factory.isPresent()) {
//            LOG.debug("Using queue from factory");
//            dispatchQueue = factory.get().getQueue(asyncPolicy, sinkModule.getId(),
//                    sinkModule::marshalSingleMessage, sinkModule::unmarshalSingleMessage);
//        } else {
//            int size = asyncPolicy.getQueueSize();
//            LOG.debug("Using default in memory queue of size {}", size);
//            dispatchQueue = new DefaultQueue<>(size);
//        }

        this.atomicResultQueue = new AtomicResultQueue<>(dispatchQueue);

        metricRegistry.register(MetricRegistry.name(this.id, "queue-size"), (Gauge<Integer>) activeDispatchers::get);

        this.droppedCounter = metricRegistry.counter(MetricRegistry.name(this.id, "dropped"));

        this.executor = Executors.newFixedThreadPool(asyncPolicy.getNumThreads());

        this.startDrainingQueue();
    }

    private void dispatchFromQueue() {
        while (true) {
            try {
                LOG.trace("Asking dispatch queue for the next entry...");
                Map.Entry<String, S> messageEntry = this.atomicResultQueue.dequeue();
                LOG.trace("Received message entry from dispatch queue {}", messageEntry);
                this.activeDispatchers.incrementAndGet();
                LOG.trace("Sending message {} via sync dispatcher", messageEntry);
                this.syncDispatcher.send(messageEntry.getValue());
                LOG.trace("Successfully sent message {}", messageEntry);

                if (messageEntry.getKey() != null) {
                    LOG.trace("Attempting to complete future for message {}", messageEntry);
                    CompletableFuture<DispatchStatus> messageFuture = this.futureMap.remove(messageEntry.getKey());

                    if (messageFuture != null) {
                        messageFuture.complete(DispatchStatus.DISPATCHED);
                        LOG.trace("Completed future for message {}", messageEntry);
                    } else {
                        LOG.warn("No future found for message {}", messageEntry);
                        this.missedFutures.incrementAndGet();
                    }
                } else {
                    LOG.trace("Dequeued an entry with a null key");
                }

                this.activeDispatchers.decrementAndGet();
            } catch (final InterruptedException e) {
                break;
            } catch (final Exception e) {
                LOG.warn("Encountered exception while taking from dispatch queue", e);
            }
        }
    }

    private void startDrainingQueue() {
        for (int i = 0; i < this.asyncPolicy.getNumThreads(); i++) {
            this.executor.execute(this::dispatchFromQueue);
        }
    }

    @Override
    public CompletableFuture<DispatchStatus> send(S message) {
        final CompletableFuture<DispatchStatus> sendFuture = new CompletableFuture<>();

        if (!this.asyncPolicy.isBlockWhenFull() && this.atomicResultQueue.isFull()) {
            this.droppedCounter.inc();
            sendFuture.completeExceptionally(new RuntimeException("Dispatch queue full"));
            return sendFuture;
        }

        try {
            final String newId = UUID.randomUUID().toString();
            this.futureMap.put(newId, sendFuture);
            this.atomicResultQueue.enqueue(message, newId, result -> {
                LOG.trace("Result of enqueueing for Id {} was {}", newId, result);

                if (result == DispatchQueue.EnqueueResult.DEFERRED) {
                    this.futureMap.remove(newId);
                    sendFuture.complete(DispatchStatus.QUEUED);
                }
            });
        } catch (final IOException | InterruptedException e) {
            sendFuture.completeExceptionally(e);
        }

        return sendFuture;
    }

    @VisibleForTesting
    public long getMissedFutures() {
        return missedFutures.get();
    }

    /**
     * This class serves to ensure operations of enqueueing a message and acting on the result of that enqueue are done
     * atomically from the point of view of any thread calling dequeue.
     */
    private static final class AtomicResultQueue<T> {
        private final Map<String, CountDownLatch> resultRecordedMap = new ConcurrentHashMap<>();
        private final DispatchQueue<T> dispatchQueue;

        private AtomicResultQueue(DispatchQueue<T> dispatchQueue) {
            this.dispatchQueue = Objects.requireNonNull(dispatchQueue);
        }

        void enqueue(T message, String key, Consumer<DispatchQueue.EnqueueResult> onEnqueue) throws IOException, InterruptedException {
            CountDownLatch resultRecorded = new CountDownLatch(1);
            this.resultRecordedMap.put(key, resultRecorded);
            DispatchQueue.EnqueueResult result = this.dispatchQueue.enqueue(message, key);

            // When the result is DEFERRED we should not track the future so remove it from the map
            if (result == DispatchQueue.EnqueueResult.DEFERRED) {
                this.resultRecordedMap.remove(key);
            }

            onEnqueue.accept(result);
            resultRecorded.countDown();
        }

        Map.Entry<String, T> dequeue() throws InterruptedException {
            Map.Entry<String, T> messageEntry = this.dispatchQueue.dequeue();

            // If the key is null, we weren't tracking it so we don't need to synchronize
            if (messageEntry.getKey() == null) {
                return messageEntry;
            }
            CountDownLatch resultRecorded = this.resultRecordedMap.remove(messageEntry.getKey());
            if (resultRecorded != null) {
                resultRecorded.await();
            }

            return messageEntry;
        }

        boolean isFull() {
            return this.dispatchQueue.isFull();
        }

        int getSize() {
            return this.dispatchQueue.getSize();
        }
    }
    
    @Override
    public int getQueueSize() {
        return atomicResultQueue.getSize();
    }

    @Override
    public void close() throws Exception {
//        state.getMetrics().remove(queueSizeMetricName());
        syncDispatcher.close();
        executor.shutdown();
    }

    /**
     * This class is intended to be used only when a suitable implementation could not be found at runtime. This should
     * only occur in testing.
     */
    private static final class DefaultQueue<T> implements DispatchQueue<T> {
        private final BlockingQueue<Map.Entry<String, T>> queue;

        private DefaultQueue(int size) {
            this.queue = new LinkedBlockingQueue<>(size);
        }

        @Override
        public EnqueueResult enqueue(T message, String key) throws InterruptedException {
            this.queue.put(new AbstractMap.SimpleImmutableEntry<>(key, message));
            return EnqueueResult.IMMEDIATE;
        }

        @Override
        public Map.Entry<String, T> dequeue() throws InterruptedException {
            return this.queue.take();
        }

        @Override
        public boolean isFull() {
            return this.queue.remainingCapacity() <= 0;
        }

        @Override
        public int getSize() {
            return this.queue.size();
        }
    }

}
