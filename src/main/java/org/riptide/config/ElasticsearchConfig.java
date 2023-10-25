package org.riptide.config;

public final class ElasticsearchConfig {
    public String host = "localhost";
    public int port = 9200;
    public String indexPrefix = "riptide-netflow-";
    public Integer numberOfShards;
    public Integer numberOfReplicas;
    public Integer routingPartitionSize;
}
