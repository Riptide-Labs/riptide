package org.riptide.e2e;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@Testcontainers
public class FlowProcessingTest {

    @Container
    public static ElasticsearchContainer elastic = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.1")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @Container
    public RiptideContainer riptideContainer = new RiptideContainer()
            .withElastic(elastic)
            .dependsOn(elastic);

    @Test
    public void init() {
        log.info("Hello World!");
        log.info("Info: {}", elastic.getMappedPort(9200));
        Assertions.assertThat(riptideContainer.isRunning()).isTrue();
    }
}
