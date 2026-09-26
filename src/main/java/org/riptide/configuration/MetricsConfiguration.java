/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.metrics.MetricSink;
import org.riptide.metrics.MetricsConfig;
import org.riptide.metrics.NoopMetricSink;
import org.riptide.metrics.PrometheusRemoteWriteSink;
import org.riptide.secrets.SecretResolvers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfiguration {

    @Bean(destroyMethod = "stop")
    public MetricSink metricSink(final MetricsConfig config, final SecretResolvers secretResolvers,
                                 final OutboundHttpTrust trust, final MetricRegistry metrics) {
        if (!config.getRemoteWrite().enabled()) {
            return new NoopMetricSink();
        }
        final var sink = new PrometheusRemoteWriteSink(config.getRemoteWrite(), secretResolvers, trust, metrics);
        sink.start();
        return sink;
    }
}
