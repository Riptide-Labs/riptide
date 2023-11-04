package org.riptide.repository.elastic.bulk;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;

public abstract class BulkUtils {

    private BulkUtils() {

    }

    protected static Exception convertToException(String error) {
        final var dto = parse(error);
        return convertToException(dto);
    }

    @VisibleForTesting
    protected static BulkErrorDto parse(String error) {
        return new Gson().fromJson(error, BulkErrorDto.class);
    }

    private static Exception convertToException(BulkErrorDto dto) {
        if (dto == null) {
            return null;
        }
        final var message = String.format("%s: %s", dto.getType(), dto.getReason());
        return new Exception(message, convertToException(dto.getCause()));
    }
}


