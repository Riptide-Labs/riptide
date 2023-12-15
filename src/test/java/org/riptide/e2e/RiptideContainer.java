package org.riptide.e2e;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InternetProtocol;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

public class RiptideContainer extends GenericContainer<RiptideContainer> {

    public RiptideContainer(String version) {
        super("quay.io/pikkalabs/riptide:%s".formatted(version));
    }

    public RiptideContainer() {
        this("local");
    }

    public RiptideContainer withElastic(ElasticsearchContainer elastic) {
        withEnv("RIPTIDE_ELASTIC_URL", "http://%s:%s".formatted(elastic.getNetworkAliases().get(1), 9200));
        // TODO MVr make this dynamic
        addFixedExposedPort(9999, 9999, InternetProtocol.UDP);
        return this;
    }
}
