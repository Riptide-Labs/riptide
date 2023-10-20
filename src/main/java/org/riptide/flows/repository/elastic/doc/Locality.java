package org.riptide.flows.repository.elastic.doc;

import java.util.Objects;

import com.google.gson.annotations.SerializedName;
import org.riptide.flows.Flow;

public enum Locality {
    @SerializedName("public")
    PUBLIC("public"),
    @SerializedName("private")
    PRIVATE("private");

    private final String value;

    private Locality(String value) {
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
