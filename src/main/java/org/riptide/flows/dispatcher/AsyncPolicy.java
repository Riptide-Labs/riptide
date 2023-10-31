package org.riptide.flows.dispatcher;

/**
 * Defines the behavior of asynchronous dispatching.
 *
 * @author jwhite
 */
public final class AsyncPolicy {

    private final int queueSize;
    private final int numThreads;
    private final boolean blockWhenFull;

    private AsyncPolicy(final Builder builder) {
        this.queueSize = builder.queueSize;
        this.numThreads = builder.numThreads;
        this.blockWhenFull = builder.blockWhenFull;
    }

    /**
     * Maximum number of messages that can be queued awaiting
     * for dispatch.
     *
     * @return queue size
     */
    public int getQueueSize() { return this.queueSize; }

    /**
     * Number of background threads that will be used to
     * dispatch messages from the queue.
     *
     * @return number of threads
     */
    public int getNumThreads() { return this.numThreads; }

    /**
     * Used to control the behavior of a dispatch when the queue
     * is full.
     *
     * When <code>true</code> the calling thread will be blocked
     * until the queue can accept the message, or the thread is
     * interrupted.
     *
     * When <code>false</code> the dispatch will return a future
     * with a {@link java.util.concurrent.RejectedExecutionException}/
     *
     * @return whether or not the thread calling dispatch
     * should block when the queue is full
     */
    public boolean isBlockWhenFull() { return this.blockWhenFull; }

    public static final class Builder {
        private int queueSize = 1000;
        private int numThreads = 10;
        private boolean blockWhenFull = false;

        private Builder() { }

        public Builder withQueueSize(final int queueSize) {
            this.queueSize = queueSize;
            return this;
        }

        public Builder withNumThreads(final int numThreads) {
            this.numThreads = numThreads;
            return this;
        }

        public Builder withBlockWhenFull(final boolean blockWhenFull) {
            this.blockWhenFull = blockWhenFull;
            return this;
        }

        public AsyncPolicy build() {
            return new AsyncPolicy(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
