package org.riptide.repository.elastic.doc;

import com.google.gson.annotations.SerializedName;

public enum Direction {
    @SerializedName("ingress")
    INGRESS,
    @SerializedName("egress")
    EGRESS,
    @SerializedName("unknown")
    UNKNOWN;
}
