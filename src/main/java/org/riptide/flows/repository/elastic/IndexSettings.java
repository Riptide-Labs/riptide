package org.riptide.flows.repository.elastic;

public class IndexSettings {

    private String indexPrefix;

    private Integer numberOfShards;

    private Integer numberOfReplicas;

    private Integer routingPartitionSize;

    private String refreshInterval;

    public String getIndexPrefix() {
        return this.indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    public Integer getNumberOfShards() {
        return this.numberOfShards;
    }

    public void setNumberOfShards(Integer numberOfShards) {
        this.numberOfShards = numberOfShards;
    }


    public Integer getNumberOfReplicas() {
        return this.numberOfReplicas;
    }

    public void setNumberOfReplicas(Integer numberOfReplicas) {
        this.numberOfReplicas = numberOfReplicas;
    }

    public Integer getRoutingPartitionSize() {
        return this.routingPartitionSize;
    }

    public void setRoutingPartitionSize(Integer routingPartitionSize) {
        this.routingPartitionSize = routingPartitionSize;
    }

    public String getRefreshInterval() {
        return this.refreshInterval;
    }

    public boolean isEmpty() {
        return this.indexPrefix == null
                && this.numberOfShards == null
                && this.numberOfReplicas == null
                && this.routingPartitionSize == null
                && this.refreshInterval == null;
    }
}
