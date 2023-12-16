package org.riptide.repository.elastic.doc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import org.riptide.flows.parser.data.Flow;

import java.util.Objects;

public enum Locality {
    @SerializedName("public")
    @JsonProperty("public")
    PUBLIC("public"),
    @SerializedName("private")
    @JsonProperty("private")
    PRIVATE("private");

    private final String value;

    Locality(String value) {
        this.value = Objects.requireNonNull(value);
    }

    public String getValue() {
        return value;
    }

    public static Locality from(Flow.Locality locality) {
        return switch (locality) {
            case null -> null;
            case PUBLIC -> Locality.PUBLIC;
            case PRIVATE -> Locality.PRIVATE;
            default -> throw new IllegalStateException();
        };
    }
}
