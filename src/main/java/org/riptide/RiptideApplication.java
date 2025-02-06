package org.riptide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = {ElasticsearchClientAutoConfiguration.class, ElasticsearchRestClientAutoConfiguration.class})
@ConfigurationPropertiesScan
public class RiptideApplication {
    public static void main(final String... args) {
        SpringApplication.run(RiptideApplication.class, args);
    }
}
