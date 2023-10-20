package org.riptide.flows.repository.elastic.bulk;

import java.util.ArrayList;
import java.util.List;

import io.searchbox.action.BulkableAction;
import io.searchbox.core.Bulk;

public class BulkWrapper extends Bulk {

    public BulkWrapper(Builder builder) {
        super(builder);
    }

    public List<BulkableAction> getActions() {
        return new ArrayList<>(bulkableActions);
    }

    public int size() {
        return bulkableActions.size();
    }

    public boolean isEmpty() {
        return bulkableActions.isEmpty();
    }
}
