package org.riptide.repository.elastic.bulk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.searchbox.core.BulkResult;
import org.riptide.repository.elastic.BulkResultWrapper;

public class DefaultBulkResult<T> implements BulkResultWrapper<T> {

    private final BulkResult rawResult;

    private final List<T> documents;

    public DefaultBulkResult(BulkResult raw, List<T> documents) {
        this.rawResult = Objects.requireNonNull(raw);
        this.documents = new ArrayList<>(Objects.requireNonNull(documents));
    }

    @Override
    public boolean isSucceeded() {
        return rawResult.isSucceeded();
    }

    @Override
    public String getErrorMessage() {
        return rawResult.getErrorMessage();
    }

    @Override
    public List<FailedItem<T>> getFailedItems() {
        int j = 0;
        final List<FailedItem<T>> failedItems = new ArrayList<>();
        for (int i = 0; i < rawResult.getItems().size(); i++) {
            final BulkResult.BulkResultItem bulkResultItem = rawResult.getItems().get(i);
            if (bulkResultItem.error != null && !bulkResultItem.error.isEmpty()) {
                final Exception cause = BulkUtils.convertToException(bulkResultItem.error);
                final T failedObject = documents.get(j);
                final FailedItem<T> failedItem = new FailedItem<>(j, failedObject, cause);
                failedItems.add(failedItem);
                j++;
            }
        }
        return failedItems;
    }

    @Override
    public BulkResult getRawResult() {
        return rawResult;
    }

    @Override
    public List<T> getFailedDocuments() {
        return getFailedItems().stream().map(FailedItem::getItem).toList();
    }
}
