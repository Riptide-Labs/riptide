package org.riptide.flows.repository.elastic.doc;

import com.google.gson.annotations.SerializedName;
import org.riptide.flows.Flow;

public enum NetflowVersion {
    @SerializedName("Netflow v5")
    V5,
    @SerializedName("Netflow v9")
    V9,
    @SerializedName("IPFIX")
    IPFIX,
    @SerializedName("SFLOW")
    SFLOW;

    public static NetflowVersion from(Flow.NetflowVersion version) {
        return switch (version) {
            case null -> null;
            case V5 -> V5;
            case V9 -> V9;
            case IPFIX -> IPFIX;
            case SFLOW -> SFLOW;
            default -> throw new IllegalArgumentException("Unknown protocol version: " + version.name());
        };
    }
}
