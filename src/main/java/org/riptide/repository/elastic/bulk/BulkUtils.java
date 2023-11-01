package org.riptide.repository.elastic.bulk;

import co.elastic.clients.elasticsearch._types.ErrorCause;

public abstract class BulkUtils {

    private BulkUtils() {

    }

    protected static Exception convertToException(final ErrorCause error) {
        final var errorMessage = String.format("%s: %s", error.type(), error.reason());
        if (error.causedBy() != null) {
            return new Exception(errorMessage, convertToException(error.causedBy()));
        }
        return new Exception(errorMessage);
    }
}
