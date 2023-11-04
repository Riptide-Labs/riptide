package org.riptide.repository.elastic;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class IndexSettings {

    @SerializedName("index_prefix")
    private String indexPrefix;

    @SerializedName("number_of_shards")
    private Integer numberOfShards;

    @SerializedName("number_of_replicas")
    private Integer numberOfReplicas;

    @SerializedName("routing_partition_size")
    private Integer routingPartitionSize;

    @SerializedName("refresh_interval")
    private String refreshInterval;

    public boolean isEmpty() {
        return this.indexPrefix == null
                && this.numberOfShards == null
                && this.numberOfReplicas == null
                && this.routingPartitionSize == null
                && this.refreshInterval == null;
    }
}
