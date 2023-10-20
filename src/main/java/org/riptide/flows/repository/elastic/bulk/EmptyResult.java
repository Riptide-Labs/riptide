package org.riptide.flows.repository.elastic.bulk;

import java.util.ArrayList;
import java.util.List;

import io.searchbox.core.BulkResult;
import org.riptide.flows.repository.elastic.BulkResultWrapper;

public class EmptyResult<T> implements BulkResultWrapper<T> {
    @Override
    public boolean isSucceeded() {
        return true;
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public List<FailedItem<T>> getFailedItems() {
        return new ArrayList<>();
    }

    @Override
    public BulkResult getRawResult() {
        return null;
    }

    @Override
    public List<T> getFailedDocuments() {
        return new ArrayList<>();
    }
}
