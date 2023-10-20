package org.riptide.flows.repository.elastic.bulk;

import java.util.Objects;

public class FailedItem<T> {
    private final T item;
    private final Exception cause;
    private final int index;

    public FailedItem(int index, T failedItem, Exception cause) {
        this.index = index;
        this.item = Objects.requireNonNull(failedItem);
        this.cause = cause;
    }

    public T getItem() {
        return item;
    }

    public Exception getCause() {
        return cause;
    }

    public int getIndex() {
        return index;
    }
}
