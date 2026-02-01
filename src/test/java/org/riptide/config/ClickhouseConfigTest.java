package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.configuration.ClickhouseConfiguration;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest(properties = {
        "riptide.clickhouse.enabled=true",
        "riptide.clickhouse.endpoint=http://localhost:8123",
        "riptide.clickhouse.username=default",
        "riptide.clickhouse.password=",
        "riptide.clickhouse.database=riptide",
})
@ActiveProfiles("test")
class ClickhouseConfigTest {

    @Autowired
    private ApplicationContext springContext;

    @Test
    void verifyConfigurationIsAvailable() {
        Assertions.assertThat(springContext.getBeansOfType(ClickhouseConfig.class)).isNotEmpty();
        Assertions.assertThat(springContext.getBeansOfType(ClickhouseConfiguration.class)).isNotEmpty();
    }

    @Test
    void verifyRepositoryExposed(@Autowired List<FlowRepository> repositories) {
        Assertions.assertThat(repositories).hasSize(1);
        Assertions.assertThat(repositories.getFirst()).isInstanceOf(ClickhouseRepository.class);
    }
}