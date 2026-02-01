package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.configuration.ClickhouseConfiguration;
import org.riptide.configuration.ElasticsearchConfiguration;
import org.riptide.configuration.OpensearchConfiguration;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.opensearch.OpensearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest(properties = {
        "riptide.opensearch.enabled=true",
        "riptide.opensearch.username=dummy",
        "riptide.opensearch.password=dummy",
})
@ActiveProfiles("test")
class OpensearchConfigTest {

    @Autowired
    private ApplicationContext springContext;

    @Test
    void verifyConfigurationIsAvailable() {
        Assertions.assertThat(springContext.getBeansOfType(OpensearchConfig.class)).isNotEmpty();
        Assertions.assertThat(springContext.getBeansOfType(OpensearchConfiguration.class)).isNotEmpty();
    }

    @Test
    void verifyOtherConfigurationIsUnavailable() {
        Assertions.assertThat(springContext.getBeansOfType(ElasticsearchConfiguration.class)).isEmpty();
        Assertions.assertThat(springContext.getBeansOfType(ClickhouseConfiguration.class)).isEmpty();
    }

    @Test
    void verifyRepositoryExposed(@Autowired List<FlowRepository> repositories) {
        Assertions.assertThat(repositories).hasSize(1);
        Assertions.assertThat(repositories.getFirst()).isInstanceOf(OpensearchRepository.class);
    }
}