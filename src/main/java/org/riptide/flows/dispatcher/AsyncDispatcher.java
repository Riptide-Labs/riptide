package org.riptide.flows.dispatcher;

import java.util.concurrent.CompletableFuture;

public interface AsyncDispatcher<S> extends AutoCloseable {

    /**
     * Asynchronously send the given message.
     *
     * @param message the message to send
     * @return a future that is resolved once the message was dispatched or queued
     */
    CompletableFuture<DispatchStatus> send(S message);

    /**
     * Returns the number of messages that are currently queued
     * awaiting for dispatch.
     *
     * @return current queue size
     */
    int getQueueSize();
    
    enum DispatchStatus {
        /**
         * The message was actually dispatched.
         */
        DISPATCHED,

        /**
         * The message has been queued to be dispatched later.
         */
        QUEUED
    }

}
