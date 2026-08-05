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

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds {@code riptide.identity.*} (and the deprecated {@code riptide.location}) via the
 * Spring {@link Binder} and verifies {@link DaemonConfig#resolveIdentity()}, and covers the
 * {@code riptide.receivers.*} binding.
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

    /**
     * The spelling an operator would reach for, matching every other riptide property. Binding
     * these through a JSON mapper accepted neither the hyphens nor the {@code 5m} duration, so both
     * of these settings used to fail startup outright (#434).
     */
    @Test
    void receiverBindsKebabCaseKeysAndFriendlyDurations() {
        final var config = bind(Map.of(
                "riptide.receivers.nf9.type", "netflow9",
                "riptide.receivers.nf9.port", "9995",
                "riptide.receivers.nf9.host", "127.0.0.1",
                "riptide.receivers.nf9.flow-active-timeout-fallback", "5m",
                "riptide.receivers.nf9.flow-inactive-timeout-fallback", "30s",
                "riptide.receivers.nf9.flow-sampling-interval-fallback", "100"));

        final var receiver = config.getReceivers().get("nf9");
        assertThat(receiver).isInstanceOf(ReceiverConfig.Neflow9Config.class);
        final var nf9 = (ReceiverConfig.Neflow9Config) receiver;
        assertThat(nf9.getPort()).isEqualTo(9995);
        assertThat(nf9.getHost()).isEqualTo("127.0.0.1");
        assertThat(nf9.getFlowActiveTimeoutFallback()).isEqualTo(Duration.ofMinutes(5));
        assertThat(nf9.getFlowInactiveTimeoutFallback()).isEqualTo(Duration.ofSeconds(30));
        assertThat(nf9.getFlowSamplingIntervalFallback()).isEqualTo(100L);
    }

    /** Relaxed binding: the camelCase spelling that used to be the only one accepted still works. */
    @Test
    void receiverBindsCamelCaseKeysAndIsoDurations() {
        final var config = bind(Map.of(
                "riptide.receivers.nf9.type", "netflow9",
                "riptide.receivers.nf9.flowActiveTimeoutFallback", "PT5M"));

        final var nf9 = (ReceiverConfig.Neflow9Config) config.getReceivers().get("nf9");
        assertThat(nf9.getFlowActiveTimeoutFallback()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void everyReceiverTypeBindsToItsConfig() {
        final var config = bind(Map.of(
                "riptide.receivers.a.type", "netflow5",
                "riptide.receivers.b.type", "netflow9",
                "riptide.receivers.c.type", "ipfix",
                "riptide.receivers.d.type", "sflow",
                "riptide.receivers.e.type", "multi"));

        assertThat(config.getReceivers().get("a")).isInstanceOf(ReceiverConfig.Neflow5Config.class);
        assertThat(config.getReceivers().get("b")).isInstanceOf(ReceiverConfig.Neflow9Config.class);
        assertThat(config.getReceivers().get("c")).isInstanceOf(ReceiverConfig.IpfixConfig.class);
        assertThat(config.getReceivers().get("d")).isInstanceOf(ReceiverConfig.SflowConfig.class);
        assertThat(config.getReceivers().get("e")).isInstanceOf(ReceiverConfig.MultiConfig.class);
        assertThat(config.getReceivers().get("a").getType()).isEqualTo("netflow5");
    }

    @Test
    void ipfixTransportAndMultiToggleBind() {
        final var config = bind(Map.of(
                "riptide.receivers.ipfix.type", "ipfix",
                "riptide.receivers.ipfix.transport", "TCP",
                "riptide.receivers.all.type", "multi",
                "riptide.receivers.all.sflow", "false"));

        final var ipfix = (ReceiverConfig.IpfixConfig) config.getReceivers().get("ipfix");
        assertThat(ipfix.getTransport()).isEqualTo(ReceiverConfig.IpfixConfig.Transport.TCP);
        final var multi = (ReceiverConfig.MultiConfig) config.getReceivers().get("all");
        assertThat(multi.isSflow()).isFalse();
        assertThat(multi.isNetflow9()).isTrue();
    }

    /** A typo in the type has to name the receiver and the accepted values, not just fail. */
    @Test
    void unknownReceiverTypeIsRejectedWithAUsefulMessage() {
        assertThatThrownBy(() -> bind(Map.of("riptide.receivers.oops.type", "netflow6")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("riptide.receivers.oops.type")
                .hasMessageContaining("netflow6")
                .hasMessageContaining("netflow9");
    }

    @Test
    void missingReceiverTypeIsRejectedWithAUsefulMessage() {
        assertThatThrownBy(() -> bind(Map.of("riptide.receivers.oops.port", "9995")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("riptide.receivers.oops.type")
                .hasMessageContaining("is not set");
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
