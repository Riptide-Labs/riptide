package org.riptide.repository.elastic.doc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;

public enum Direction {
    @SerializedName("ingress")
    @JsonProperty("ingress")
    INGRESS,
    @SerializedName("egress")
    @JsonProperty("egress")
    EGRESS,
    @SerializedName("unknown")
    @JsonProperty("unknown")
    UNKNOWN;
}
