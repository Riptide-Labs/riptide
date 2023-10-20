package org.riptide.flows.repository.elastic.bulk;

import org.riptide.flows.repository.elastic.BulkResultWrapper;

import java.io.IOException;
import java.util.Objects;

public class BulkException extends IOException {

    private static final String ERROR_MESSAGE = "Could not perform bulk operation";

    private BulkResultWrapper bulkResult;

    public BulkException(BulkResultWrapper bulkResultWrapper) {
        super(ERROR_MESSAGE);
        this.bulkResult = Objects.requireNonNull(bulkResultWrapper);
    }

    public BulkException() {
        super(ERROR_MESSAGE);
    }

    public BulkException(IOException ex) {
        super(ERROR_MESSAGE, ex);
    }

    public BulkResultWrapper getBulkResult() {
        return bulkResult;
    }
}
