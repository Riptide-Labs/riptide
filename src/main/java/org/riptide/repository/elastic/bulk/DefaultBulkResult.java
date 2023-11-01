package org.riptide.repository.elastic.bulk;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import org.riptide.repository.elastic.BulkResultWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefaultBulkResult<T> implements BulkResultWrapper<T> {

    private final BulkResponse rawResult;

    private final List<T> documents;

    public DefaultBulkResult(BulkResponse raw, List<T> documents) {
        this.rawResult = Objects.requireNonNull(raw);
        this.documents = new ArrayList<>(Objects.requireNonNull(documents));
    }

    @Override
    public boolean isSucceeded() {
        return !rawResult.errors();
    }

    @Override
    public List<FailedItem<T>> getFailedItems() {
        int j = 0;
        final List<FailedItem<T>> failedItems = new ArrayList<>();
        for (int i = 0; i < rawResult.items().size(); i++) {
            final var bulkResultItem = rawResult.items().get(i);
            if (bulkResultItem.error() != null) {
                final Exception cause = BulkUtils.convertToException(bulkResultItem.error());
                final T failedObject = documents.get(j);
                final FailedItem<T> failedItem = new FailedItem<>(j, failedObject, cause);
                failedItems.add(failedItem);
                j++;
            }
        }
        return failedItems;
    }

    @Override
    public List<T> getFailedDocuments() {
        return getFailedItems().stream().map(FailedItem::getItem).toList();
    }
}
