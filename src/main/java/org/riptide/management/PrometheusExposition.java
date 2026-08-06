/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders a Dropwizard {@link MetricRegistry} as Prometheus text exposition format 0.0.4.
 *
 * <p>Hand-written rather than pulled from a bridge library on purpose. The registry uses five
 * metric types and the format is a dozen lines of rules, whereas the available Dropwizard-to-
 * Prometheus bridges track the Prometheus Java client's own major versions and would add three
 * artifacts to a dependency tree this project deliberately keeps small.
 *
 * <p>Metric names are emitted as they appear in the registry, with characters Prometheus does not
 * allow replaced by {@code _}. Counters are <em>not</em> given the conventional {@code _total}
 * suffix: an operator reading {@code MetricRegistry.name(...)} in the source should be able to
 * search for that same string in Grafana. Prometheus accepts the shorter form.
 *
 * <p>Timer durations are converted from Dropwizard's nanoseconds to seconds, which is the unit
 * Prometheus tooling assumes.
 */
final class PrometheusExposition {

    private PrometheusExposition() {
    }

    static String render(final MetricRegistry registry) {
        final StringBuilder out = new StringBuilder(4096);

        for (final Map.Entry<String, Gauge> entry : registry.getGauges().entrySet()) {
            // Non-numeric gauges (riptide has string-valued ones) have no Prometheus
            // representation; skipping beats inventing one.
            if (entry.getValue().getValue() instanceof Number value) {
                final String name = sanitize(entry.getKey());
                type(out, name, "gauge");
                sample(out, name, value.doubleValue());
            }
        }

        for (final Map.Entry<String, Counter> entry : registry.getCounters().entrySet()) {
            final String name = sanitize(entry.getKey());
            type(out, name, "counter");
            sample(out, name, entry.getValue().getCount());
        }

        for (final Map.Entry<String, Meter> entry : registry.getMeters().entrySet()) {
            final String name = sanitize(entry.getKey());
            final Meter meter = entry.getValue();
            type(out, name, "counter");
            sample(out, name, meter.getCount());
            // Dropwizard's own moving averages. Prometheus would normally derive a rate from the
            // counter, but exporting these costs nothing and they are what riptide's existing
            // meters were created to show.
            type(out, name + "_rate_1m", "gauge");
            sample(out, name + "_rate_1m", meter.getOneMinuteRate());
            type(out, name + "_rate_5m", "gauge");
            sample(out, name + "_rate_5m", meter.getFiveMinuteRate());
        }

        for (final Map.Entry<String, Histogram> entry : registry.getHistograms().entrySet()) {
            final String name = sanitize(entry.getKey());
            final Histogram histogram = entry.getValue();
            type(out, name, "summary");
            quantiles(out, name, histogram.getSnapshot(), 1.0d);
            sample(out, name + "_count", histogram.getCount());
        }

        for (final Map.Entry<String, Timer> entry : registry.getTimers().entrySet()) {
            final String name = sanitize(entry.getKey()) + "_seconds";
            final Timer timer = entry.getValue();
            type(out, name, "summary");
            quantiles(out, name, timer.getSnapshot(), 1.0d / TimeUnit.SECONDS.toNanos(1L));
            sample(out, name + "_count", timer.getCount());
        }

        return out.toString();
    }

    private static void quantiles(final StringBuilder out, final String name,
                                  final Snapshot snapshot, final double scale) {
        quantile(out, name, "0.5", snapshot.getMedian() * scale);
        quantile(out, name, "0.95", snapshot.get95thPercentile() * scale);
        quantile(out, name, "0.99", snapshot.get99thPercentile() * scale);
    }

    private static void quantile(final StringBuilder out, final String name,
                                 final String q, final double value) {
        out.append(name).append("{quantile=\"").append(q).append("\"} ").append(value).append('\n');
    }

    private static void type(final StringBuilder out, final String name, final String type) {
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(final StringBuilder out, final String name, final double value) {
        out.append(name).append(' ').append(value).append('\n');
    }

    /** Prometheus metric names are {@code [a-zA-Z_:][a-zA-Z0-9_:]*}; the registry uses dots. */
    private static String sanitize(final String name) {
        final StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean valid = c == '_' || c == ':'
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && c >= '0' && c <= '9');
            sanitized.append(valid ? c : '_');
        }
        return sanitized.toString();
    }
}
