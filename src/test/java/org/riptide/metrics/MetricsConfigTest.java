/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsConfigTest {

    @Test
    void defaultsMatchTheSpec() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();

        assertThat(remoteWrite.getBatch().getMaxSamples()).isEqualTo(5_000);
        assertThat(remoteWrite.getBatch().getMaxLatency()).isEqualTo(Duration.ofSeconds(2));
        assertThat(remoteWrite.getQueueCapacity()).isEqualTo(2_000_000);
        assertThat(remoteWrite.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(5));
        assertThat(remoteWrite.getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void unsetUrlDisablesTheSink() {
        assertThat(new MetricsConfig.RemoteWrite().enabled()).isFalse();
    }

    @Test
    void aSetUrlEnablesTheSink() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setUrl("http://localhost:8480/api/v1/write");

        assertThat(remoteWrite.enabled()).isTrue();
    }

    @Test
    void everyPropertyBindsFromRelaxedNames() {
        final var environment = new MockEnvironment()
                .withProperty("riptide.metrics.remote-write.url", "http://localhost:8480/api/v1/write")
                .withProperty("riptide.metrics.remote-write.bearer-token", "s3cr3t")
                .withProperty("riptide.metrics.remote-write.batch.max-samples", "1000")
                .withProperty("riptide.metrics.remote-write.batch.max-latency", "3s")
                .withProperty("riptide.metrics.remote-write.queue-capacity", "500000")
                .withProperty("riptide.metrics.remote-write.shutdown-grace-period", "10s")
                .withProperty("riptide.metrics.remote-write.max-attempts", "5")
                .withProperty("riptide.metrics.remote-write.retry-backoff", "2s");

        final var bound = new Binder(ConfigurationPropertySources.get(environment))
                .bind("riptide.metrics", MetricsConfig.class)
                .orElseThrow(() -> new AssertionError("nothing bound from riptide.metrics.*"));

        final var remoteWrite = bound.getRemoteWrite();
        assertThat(remoteWrite.getUrl()).isEqualTo("http://localhost:8480/api/v1/write");
        assertThat(remoteWrite.getBearerToken().getValue()).isEqualTo("s3cr3t");
        assertThat(remoteWrite.getBatch().getMaxSamples()).isEqualTo(1000);
        assertThat(remoteWrite.getBatch().getMaxLatency()).isEqualTo(Duration.ofSeconds(3));
        assertThat(remoteWrite.getQueueCapacity()).isEqualTo(500_000);
        assertThat(remoteWrite.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
        assertThat(remoteWrite.getMaxAttempts()).isEqualTo(5);
        assertThat(remoteWrite.getRetryBackoff()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void validateNamesTheFullKeyForANonPositiveMaxSamples() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.getBatch().setMaxSamples(0);

        assertThatThrownBy(remoteWrite::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.metrics.remote-write.batch.max-samples");
    }

    @Test
    void validateNamesTheFullKeyForANegativeQueueCapacity() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setQueueCapacity(-1);

        assertThatThrownBy(remoteWrite::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.metrics.remote-write.queue-capacity");
    }

    @Test
    void validateRefusesAUrlWhoseSchemeIsNotHttpOrHttps() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setUrl("ftp://vminsert:8480/api/v1/write");

        assertThatThrownBy(remoteWrite::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("riptide.metrics.remote-write.url must be an http or https URL (got scheme ftp)");
    }

    @Test
    void validateAcceptsAnHttpsUrl() {
        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setUrl("https://vminsert:8480/api/v1/write");

        assertThatCode(remoteWrite::validate).doesNotThrowAnyException();
    }

    @Test
    void validatePassesWithDefaults() {
        assertThatCode(() -> new MetricsConfig.RemoteWrite().validate()).doesNotThrowAnyException();
    }
}
