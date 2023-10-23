package org.riptide.repository.elastic;

import java.util.List;

import io.searchbox.core.BulkResult;
import org.riptide.repository.elastic.bulk.FailedItem;

public interface BulkResultWrapper<T> {
    boolean isSucceeded();

    String getErrorMessage();

    BulkResult getRawResult();

    List<FailedItem<T>> getFailedItems();

    List<T> getFailedDocuments();
}
