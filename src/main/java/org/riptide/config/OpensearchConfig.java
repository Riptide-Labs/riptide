package org.riptide.config;

public class OpensearchConfig {
        public String host = "localhost";
        public int port = 9200;
        public String username;
        public String password;
        public String index = "riptide-netflow";
        public Integer numberOfShards = 1;
        public Integer numberOfReplicas = 1;
}
