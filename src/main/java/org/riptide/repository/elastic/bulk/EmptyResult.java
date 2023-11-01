package org.riptide.repository.elastic.bulk;

import org.riptide.repository.elastic.BulkResultWrapper;

import java.util.ArrayList;
import java.util.List;

public class EmptyResult<T> implements BulkResultWrapper<T> {
    @Override
    public boolean isSucceeded() {
        return true;
    }

    @Override
    public List<FailedItem<T>> getFailedItems() {
        return new ArrayList<>();
    }

    @Override
    public List<T> getFailedDocuments() {
        return new ArrayList<>();
    }
}
