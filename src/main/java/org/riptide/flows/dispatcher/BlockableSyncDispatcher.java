package org.riptide.flows.dispatcher;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockableSyncDispatcher<S> implements SyncDispatcher<S> {

    private final DispatchThreadLatch dispatchThreadLatch = new DispatchThreadLatch();
    private final AtomicInteger blockedThreads = new AtomicInteger(0);
    private final AtomicInteger numDispatched = new AtomicInteger(0);
    private final List<S> dispatchedMessages = new CopyOnWriteArrayList<>();

    @Override
    public void send(S message) {
        blockedThreads.incrementAndGet();
        dispatchThreadLatch.await();
        numDispatched.incrementAndGet();
        dispatchedMessages.add(message);
        blockedThreads.decrementAndGet();
    }

    @Override
    public void close() {
        // pass
    }

    public void block() {
        dispatchThreadLatch.lock();
    }

    public void unblock() {
        dispatchThreadLatch.unlock();
    }

    public int getBlockedThreadCount() {
        return blockedThreads.get();
    }

    public int getNumMessageDispatched() {
        return numDispatched.get();
    }

    public List<S> getDispatchedMessages() {
        return Collections.unmodifiableList(dispatchedMessages);
    }

    private static class DispatchThreadLatch {
        private boolean blocked = false;

        synchronized void await() {
            try {
                while (blocked) {
                    wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void lock() {
            blocked = true;
        }

        synchronized void unlock() {
            blocked = false;
            notifyAll();
        }
    }

}
