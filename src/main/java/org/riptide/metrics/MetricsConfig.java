/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "riptide.metrics")
public final class MetricsConfig {

    private RemoteWrite remoteWrite = new RemoteWrite();

    /**
     * A Prometheus remote-write 1.0 sink. Unset {@link #url} disables it. Samples then go to
     * {@link NoopMetricSink} instead ({@code MetricsConfiguration}).
     */
    @Data
    public static final class RemoteWrite {
        /** The full write URL, tenant path included for a VictoriaMetrics cluster. Unset disables the sink. */
        private String url;
        /** Sent as {@code Authorization: Bearer <token>} when set. Resolved on every flush so a rotation takes effect. */
        private SecretRef bearerToken;
        private Batch batch = new Batch();
        private int queueCapacity = 2_000_000;
        private Duration shutdownGracePeriod = Duration.ofSeconds(5);
        /** Attempts (including the first) before a batch is dropped on a retryable status. */
        private int maxAttempts = 3;
        /** Doubles on each retry: attempt 2 waits this long, attempt 3 waits twice this, and so on. */
        private Duration retryBackoff = Duration.ofSeconds(1);

        public boolean enabled() {
            return this.url != null && !this.url.isBlank();
        }

        /**
         * Fail fast on values that would misbehave at runtime; called when the sink is
         * constructed. Every message names the full key, matching {@code
         * ClickhouseConfig.BatchConfig#validate}'s convention.
         */
        public void validate() {
            final int maxSamples = this.batch.getMaxSamples();
            final Duration maxLatency = this.batch.getMaxLatency();
            if (maxSamples <= 0) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.batch.max-samples must be > 0 (got " + maxSamples + ")");
            }
            if (maxLatency == null || maxLatency.isZero() || maxLatency.isNegative()) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.batch.max-latency must be > 0 (got " + maxLatency + ")");
            }
            if (this.queueCapacity <= 0) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.queue-capacity must be > 0 (got " + this.queueCapacity + ")");
            }
            if (this.maxAttempts <= 0) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.max-attempts must be > 0 (got " + this.maxAttempts + ")");
            }
            if (this.retryBackoff == null || this.retryBackoff.isZero() || this.retryBackoff.isNegative()) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.retry-backoff must be > 0 (got " + this.retryBackoff + ")");
            }
            if (this.shutdownGracePeriod == null
                    || this.shutdownGracePeriod.compareTo(maxLatency.multipliedBy(2)) < 0) {
                throw new IllegalArgumentException(
                        "riptide.metrics.remote-write.shutdown-grace-period (" + this.shutdownGracePeriod
                                + ") must be at least twice batch.max-latency (" + maxLatency + ")");
            }
            if (this.enabled()) {
                final URI uri;
                try {
                    uri = new URI(this.url);
                    uri.toURL();
                } catch (final URISyntaxException | MalformedURLException | IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "riptide.metrics.remote-write.url is not a URL: " + e.getMessage(), e);
                }
                // toURL() accepts every scheme the JDK has a handler for (file, ftp, jar), and the
                // sink's HTTP client can send none of them
                if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalArgumentException(
                            "riptide.metrics.remote-write.url must be an http or https URL (got scheme "
                                    + uri.getScheme() + ")");
                }
            }
        }
    }

    @Data
    public static final class Batch {
        private int maxSamples = 5_000;
        private Duration maxLatency = Duration.ofSeconds(2);
    }
}
