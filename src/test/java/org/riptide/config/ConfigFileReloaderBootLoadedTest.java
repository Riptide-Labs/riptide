/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.Inventory;
import org.riptide.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reloader against a config file that boot really loaded (#889). {@link ConfigFileReloaderTest}
 * cannot reach this case: its import comes from {@code @DynamicPropertySource}, which is applied
 * after ConfigData, so its file is never part of boot. Here the import is an inlined test property,
 * which ConfigData does process, and the file is written before the context starts.
 */
@SpringBootTest(properties = {
        "spring.config.import=file:" + ConfigFileReloaderBootLoadedTest.CONFIG,
        "riptide.inventory.file=" + ConfigFileReloaderBootLoadedTest.INVENTORY,
        "riptide.config.reload-interval=1h"
})
class ConfigFileReloaderBootLoadedTest {

    // fixed paths: an annotation value must be a constant
    static final String CONFIG = "target/config-reloader-boot-loaded-test/config.yaml";
    static final String INVENTORY = "target/config-reloader-boot-loaded-test/inventory.yaml";

    static {
        try {
            Files.createDirectories(Path.of(CONFIG).getParent());
            Files.writeString(Path.of(CONFIG), """
                    riptide:
                      snmp:
                        credentials:
                          at-boot:
                            version: v3
                            security-name: monitoring
                    """);
            Files.writeString(Path.of(INVENTORY), """
                    riptide:
                      snmp:
                        agents: {}
                      exporters:
                        neutral:
                          address: 198.51.100.1
                    """);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private ConfigFileReloader reloader;

    @Autowired
    private MetricRegistry metrics;

    @Autowired
    private Inventory inventory;

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void theFirstPollDoesNotReloadAFileUnchangedSinceBoot() throws Exception {
        // without this the test is blind: a file boot never loaded makes the first poll a real
        // commit, which is the case ConfigFileReloaderTest already covers
        assertThat(this.environment.getPropertySources().stream().map(PropertySource::getName))
                .as("boot must have loaded the config file, or this test proves nothing")
                .anyMatch(name -> name.contains(CONFIG));
        assertThat(this.inventory.profiles().credentials()).containsKey("at-boot");

        final long successesBefore = this.metrics.counter("config.reload.successes").getCount();
        final long partialBefore = this.metrics.counter("config.reload.partial").getCount();
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            this.reloader.poll();

            assertThat(this.metrics.counter("config.reload.successes").getCount())
                    .as("an unchanged file must not be reloaded").isEqualTo(successesBefore);
            assertThat(this.metrics.counter("config.reload.partial").getCount()).isEqualTo(partialBefore);
            assertThat((Integer) this.metrics.getGauges().get("config.reload.stale").getValue()).isZero();
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().startsWith("Config reloaded"));

            // and the reloader still reloads, or everything above holds for a dead one
            Files.writeString(Path.of(CONFIG), """
                    riptide:
                      snmp:
                        credentials:
                          after-edit:
                            version: v3
                            security-name: monitoring
                    """);
            this.reloader.poll();

            assertThat(this.metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore + 1);
            assertThat(this.inventory.profiles().credentials()).containsKey("after-edit").doesNotContainKey("at-boot");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
