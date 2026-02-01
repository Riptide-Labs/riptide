package org.riptide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RiptideApplication {
    public static void main(final String... args) {
        System.exit(SpringApplication.exit(SpringApplication.run(RiptideApplication.class, args)));
    }
}
