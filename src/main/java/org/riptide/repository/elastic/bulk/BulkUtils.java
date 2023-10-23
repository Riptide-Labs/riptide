package org.riptide.repository.elastic.bulk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public abstract class BulkUtils {

    private BulkUtils() {}

    protected static Exception convertToException(String error) {
        // Read error data
        final JsonObject errorObject = new JsonParser().parse(error).getAsJsonObject();
        final String errorType = errorObject.get("type").getAsString();
        final String errorReason = errorObject.get("reason").getAsString();
        final JsonElement errorCause = errorObject.get("caused_by");

        // Create Exception
        final String errorMessage = String.format("%s: %s", errorType, errorReason);
        if (errorCause != null) {
            return new Exception(errorMessage, convertToException(errorCause.toString()));
        }
        return new Exception(errorMessage);
    }
}
