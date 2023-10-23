package org.riptide.repository.elastic.doc;

import com.google.gson.annotations.SerializedName;
import org.riptide.flows.parser.data.Flow;

public enum Direction {
    @SerializedName("ingress")
    INGRESS,
    @SerializedName("egress")
    EGRESS,
    @SerializedName("unknown")
    UNKNOWN;

    public static Direction from(Flow.Direction direction) {
        return switch (direction) {
            case null -> UNKNOWN;
            case INGRESS -> INGRESS;
            case EGRESS -> EGRESS;
            case UNKNOWN -> UNKNOWN;
            default -> throw new IllegalArgumentException("Unknown direction: " + direction.name());
        };
    }

}
