package org.riptide.repository.elastic.doc;

import com.google.gson.annotations.SerializedName;

public enum FlowProtocol {
    @SerializedName("Netflow v5")
    NetflowV5,
    @SerializedName("Netflow v9")
    NetflowV9,
    @SerializedName("IPFIX")
    IPFIX,
    @SerializedName("SFLOW")
    SFLOW;
}
