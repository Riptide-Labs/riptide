package org.riptide.repository.elastic.doc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;

public enum FlowProtocol {
    @SerializedName("Netflow v5")
    @JsonProperty("Netflow v5")
    NetflowV5,
    @SerializedName("Netflow v9")
    @JsonProperty("Netflow v9")
    NetflowV9,
    @SerializedName("IPFIX")
    @JsonProperty("IPFIX")
    IPFIX,
    @SerializedName("SFLOW")
    @JsonProperty("SFLOW")
    SFLOW;
}
