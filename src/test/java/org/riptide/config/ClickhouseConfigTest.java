package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.repository.FlowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

@SpringBootTest(properties = {
        "riptide.clickhouse.enabled=true",
        "riptide.clickhouse.endpoint=http://localhost:8123",
        "riptide.clickhouse.username=default",
        "riptide.clickhouse.password=",
        "riptide.clickhouse.table=riptide",
})
class ClickhouseConfigTest {

    @Autowired
    private ApplicationContext springContext;

    @Test
    void verifyElasticsearchConfigurationIsAvailable() {
        Assertions.assertThat(springContext.getBeansOfType(ElasticsearchConfig.class)).isNotEmpty();
    }

    @Test
    void verifyOpensearchConfigurationIsUnavailable() {
        Assertions.assertThat(springContext.getBeansOfType(OpensearchConfig.class)).isEmpty();
    }

    @Test
    void verifyFlowRepositoryExposed(@Autowired Optional<List<FlowRepository>> repositories) {
        Assertions.assertThat(repositories).isNotEmpty();
    }

}