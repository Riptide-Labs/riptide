/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@code riptide.identity.*} (and the deprecated {@code riptide.location}) via the
 * Spring {@link Binder} and verifies {@link DaemonConfig#resolveIdentity()}.
 */
class DaemonConfigTest {

    private static DaemonConfig bind(final Map<String, Object> props) {
        final var source = new MapConfigurationPropertySource(props);
        return new Binder(source).bind("riptide", DaemonConfig.class).orElseGet(DaemonConfig::new);
    }

    @Test
    void deprecatedLocationKeyBindsToZoneWithWarning() {
        final var logger = (Logger) LoggerFactory.getLogger(DaemonConfig.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            final var config = bind(Map.of("riptide.location", "legacy-dc"));

            assertThat(config.resolveIdentity().zone()).isEqualTo("legacy-dc");
            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("riptide.location")
                                .contains("deprecated")
                                .contains("legacy-dc");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void explicitZoneWinsOverDeprecatedLocation() {
        final var config = bind(Map.of(
                "riptide.location", "legacy-dc",
                "riptide.identity.zone", "dmz"));

        assertThat(config.resolveIdentity().zone()).isEqualTo("dmz");
    }

    @Test
    void defaultsWhenUnconfigured() {
        final var identity = bind(Map.of()).resolveIdentity();

        assertThat(identity.tenant()).isEqualTo("default");
        assertThat(identity.organisation()).isEqualTo("default");
        assertThat(identity.zone()).isEqualTo("default");
        // system is host-derived and never fails startup.
        assertThat(identity.system()).isNotBlank();
    }

    @Test
    void configuredIdentityIsResolved() {
        final var identity = bind(Map.of(
                "riptide.identity.tenant", "acme",
                "riptide.identity.organisation", "acme-eu",
                "riptide.identity.zone", "dmz",
                "riptide.identity.system", "collector-01")).resolveIdentity();

        assertThat(identity.tenant()).isEqualTo("acme");
        assertThat(identity.organisation()).isEqualTo("acme-eu");
        assertThat(identity.zone()).isEqualTo("dmz");
        assertThat(identity.system()).isEqualTo("collector-01");
    }

    @Test
    void blankIdentityValuesFallBackToDefault() {
        // an explicitly empty property must not stamp an empty dimension (which would
        // also lead the ClickHouse sort key with an empty tenant)
        final var identity = bind(Map.of(
                "riptide.identity.tenant", "",
                "riptide.identity.organisation", "",
                "riptide.identity.zone", "")).resolveIdentity();

        assertThat(identity.tenant()).isEqualTo("default");
        assertThat(identity.organisation()).isEqualTo("default");
        assertThat(identity.zone()).isEqualTo("default");
        assertThat(identity.system()).isNotBlank();
    }
}
