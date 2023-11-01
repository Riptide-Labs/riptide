package org.riptide.flows.utils;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class NettyEventListener implements GenericFutureListener {
    private String name;
    private boolean isDone = false;
    public NettyEventListener(String name) {
        this.name = name;
    }

    @Override
    public void operationComplete(Future future) {
        isDone = future.isDone();
    }

    public String getName() {
        return name;
    }

    public boolean isDone() {
        return isDone;
    }
}
