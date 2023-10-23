package org.riptide.repository.elastic;

import org.riptide.repository.elastic.bulk.FailedItem;
import org.riptide.repository.elastic.doc.FlowDocument;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PersistenceException extends DetailedFlowException {

    private final List<FailedItem<FlowDocument>> failedItems;

    public PersistenceException(final String message, final List<FailedItem<FlowDocument>> failedItems) {
        super(message);

        this.failedItems = Objects.requireNonNull(failedItems);
    }

    public List<FailedItem<FlowDocument>> getFailedItems() {
        return this.failedItems;
    }

    @Override
    public List<String> getDetailedLogMessages() {
        return this.failedItems.stream()
                .map(e -> String.format("Failed to persist item with convoKey '%s' and index %d: %s", e.getItem().getConvoKey(), e.getIndex(), e.getCause().getMessage())).collect(Collectors.toList());
    }
}
