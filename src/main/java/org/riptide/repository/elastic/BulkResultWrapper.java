package org.riptide.repository.elastic;

import org.riptide.repository.elastic.bulk.FailedItem;

import java.util.List;

public interface BulkResultWrapper<T> {
    boolean isSucceeded();

    List<FailedItem<T>> getFailedItems();

    List<T> getFailedDocuments();
}
