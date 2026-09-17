/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.riptide.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a failed inventory rebuild names, in the configuration that made the old answer wrong:
 * discovery on and {@code riptide.inventory.file} unset, which is valid because the composed
 * document supplies the whole inventory.
 *
 * <p>Both rebuild-failure sentences in {@code ConfigFileReloader} were built from
 * {@code InventoryConfig.getFile()}, so this configuration printed "rebuilt from null" at the one
 * message that explains a credential rotation is not serving yet. They now name
 * {@code Inventory.documentName()}, the same spelling the inventory watcher and the loader's boot
 * errors use.</p>
 *
 * <p>Its own context, because the configuration under test is how the context boots. The endpoint
 * is a port nothing can be listening on (binding port 1 needs root), so boot degrades exactly as
 * {@code DiscoveryBootDegradedTest} covers and every later {@code text()} throws — which is the
 * rebuild failure this test needs, with no HTTP server to run.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConfigReloadRebuildSubjectTest {

    /** Nothing can listen here without root, so every fetch is a refused connection. */
    private static final String ENDPOINT = "http://127.0.0.1:1/devices";

    private static final Path CONFIG = createTempConfigPath();

    @Autowired
    private ConfigFileReloader reloader;

    private static Path createTempConfigPath() {
        try {
            return Files.createTempDirectory("riptide-rebuild-subject").resolve("config.yaml");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "optional:file:" + CONFIG);
        registry.add("riptide.discovery.url", () -> ENDPOINT);
        // an hour, so neither schedule runs on its own: this test drives the one poll it wants
        registry.add("riptide.discovery.interval", () -> "1h");
        registry.add("riptide.config.reload-interval", () -> "1h");
        // riptide.inventory.file is deliberately left unset
    }

    @Test
    void aRebuildFailureNamesTheInventorysOwnDocumentRatherThanAnUnsetFileKey() throws Exception {
        final ListAppender<ILoggingEvent> captured = capture();
        try {
            // any real edit: every committed config reload rebuilds the inventory, and the
            // rebuild reads the strict text(), which cannot reach the endpoint
            Files.writeString(CONFIG, """
                    riptide:
                      snmp:
                        credentials:
                          rotated:
                            version: v3
                            security-name: monitoring
                    """);
            this.reloader.poll();

            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("could not be rebuilt from")
                    .contains("riptide.inventory.file (unset) + " + ENDPOINT)
                    .doesNotContain("rebuilt from null")
                    // the noun as well as the subject (#803): this message referred back to the
                    // source twice more, as "the inventory file", in a configuration where no
                    // inventory file is even set
                    .contains("until the inventory source is fixed")
                    .contains("The inventory source says:")
                    .doesNotContain("inventory file is"));
        } finally {
            release(captured);
        }
    }

    private static ListAppender<ILoggingEvent> capture() {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        final ListAppender<ILoggingEvent> appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        return appender;
    }

    private static void release(final ListAppender<ILoggingEvent> appender) {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        logger.detachAppender(appender);
        appender.stop();
    }
}
