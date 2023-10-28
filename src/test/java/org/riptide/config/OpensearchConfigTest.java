package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = {
        "riptide.elastic.enabled=false", // default is true
        "riptide.opensearch.enabled=true",
        "riptide.opensearch.username=dummy",
        "riptide.opensearch.password=dummy"
})
class OpensearchConfigTest {

    @Autowired
    private ApplicationContext springContext;

    @Test
    void verifyOpensearchConfigurationIsAvailable() {
        Assertions.assertThat(springContext.getBeansOfType(OpensearchConfig.class)).isNotEmpty();
    }

    @Test
    void verifyElasticsearchConfigurationIsUnavailable() {
        Assertions.assertThat(springContext.getBeansOfType(ElasticsearchConfig.class)).isEmpty();
    }

}