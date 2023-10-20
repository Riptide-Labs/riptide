package org.riptide.flows.dispatcher;

public interface SyncDispatcher<S> extends AutoCloseable {

    void send(S message);
}
