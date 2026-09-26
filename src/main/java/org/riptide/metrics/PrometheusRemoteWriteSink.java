/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.secrets.SecretResolvers;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers samples in a bounded queue and POSTs them to a Prometheus remote-write 1.0 endpoint,
 * one background flusher at a time. Mirrors {@code BatchingFlowRepository}'s shape (bounded
 * queue, drop-not-block, {@code Queues.drain}, shutdown sweep) for an unrelated element type.
 * See that class for the loss-model rationale this repeats rather than re-derives.
 *
 * <p>A batch that a non-2xx, non-retryable status refuses, or that exhausts {@code max-attempts}
 * on a retryable one, is dropped and counted ({@code failedSamples}); nothing here retries a
 * batch that already failed to send, and there is no dead-letter path for metrics samples.
 */
@Slf4j
public final class PrometheusRemoteWriteSink implements MetricSink {

    /** Same rationale as {@code BatchingFlowRepository.OFFER_TIMEOUT_MS}: one budget per call. */
    private static final long OFFER_TIMEOUT_MS = 100;

    private static final long DROP_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final long INTERRUPT_JOIN_MS = 1_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** How much of an error response is drained so the connection can return to the pool. */
    private static final int ERROR_DRAIN_LIMIT = 64 * 1024;

    private final MetricsConfig.RemoteWrite config;
    private final SecretResolvers secretResolvers;
    private final OutboundHttpTrust trust;
    private final URL url;
    private final LinkedBlockingQueue<Sample> queue;

    private final MetricRegistry metricRegistry;
    private final String queueDepthGauge;
    private final Counter dropped;
    private final Counter failed;
    private final Counter sent;
    private final Histogram batchSize;
    private final Timer flushTimer;

    /** Set once by stop(): producers reject-new, the flusher switches to its final drain. */
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile Thread flusher;

    /** nanoTime, not wall clock: an NTP step backwards would mute drop warnings for the skew. */
    private final AtomicLong lastDropWarnNanos = new AtomicLong(System.nanoTime() - DROP_WARN_INTERVAL_NANOS);

    public PrometheusRemoteWriteSink(final MetricsConfig.RemoteWrite config, final SecretResolvers secretResolvers,
                                     final OutboundHttpTrust trust, final MetricRegistry metrics) {
        // Fail fast on nonsensical values, exactly as BatchingFlowRepository does for its config.
        config.validate();
        this.config = config;
        this.secretResolvers = secretResolvers;
        this.trust = trust;
        try {
            this.url = new URI(config.getUrl()).toURL();
        } catch (final URISyntaxException | MalformedURLException e) {
            throw new IllegalArgumentException("riptide.metrics.remote-write.url is not a URL: " + e.getMessage(), e);
        }
        this.queue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        this.metricRegistry = metrics;

        this.dropped = metrics.counter(MetricRegistry.name("metrics", "sink", "droppedSamples"));
        this.failed = metrics.counter(MetricRegistry.name("metrics", "sink", "failedSamples"));
        this.sent = metrics.counter(MetricRegistry.name("metrics", "sink", "sentSamples"));
        this.batchSize = metrics.histogram(MetricRegistry.name("metrics", "sink", "batchSize"));
        this.flushTimer = metrics.timer(MetricRegistry.name("metrics", "sink", "flush"));

        this.queueDepthGauge = MetricRegistry.name("metrics", "sink", "queueDepth");
        // Replace, don't keep: a stale gauge left by a previous instance would keep reading that
        // instance's dead queue — worse than no gauge at all. stop() unregisters it again.
        metrics.remove(this.queueDepthGauge);
        metrics.register(this.queueDepthGauge, (Gauge<Integer>) this.queue::size);
    }

    @Override
    public void accept(final List<Sample> samples) {
        // One offer budget for the whole call (see OFFER_TIMEOUT_MS): the first samples may wait
        // for space, and once the budget is spent the rest gets non-blocking offers only.
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OFFER_TIMEOUT_MS);
        for (int i = 0; i < samples.size(); i++) {
            if (this.stopped.get()) {
                drop(samples.size() - i, "sink is stopping");
                return;
            }
            try {
                final long remaining = deadline - System.nanoTime();
                final boolean accepted = remaining > 0
                        ? this.queue.offer(samples.get(i), remaining, TimeUnit.NANOSECONDS)
                        : this.queue.offer(samples.get(i));
                if (!accepted) {
                    drop(samples.size() - i, "queue is full — the remote-write endpoint cannot keep up");
                    return;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                drop(samples.size() - i, "producer interrupted");
                return;
            }
        }
    }

    private void drop(final int count, final String reason) {
        this.dropped.inc(count);
        final long now = System.nanoTime();
        final long last = this.lastDropWarnNanos.get();
        if (now - last >= DROP_WARN_INTERVAL_NANOS && this.lastDropWarnNanos.compareAndSet(last, now)) {
            log.warn("Dropping samples ({}); {} dropped in total", reason, this.dropped.getCount());
        }
    }

    public void start() {
        if (this.stopped.get()) {
            throw new IllegalStateException("PrometheusRemoteWriteSink is stopped and cannot be restarted");
        }
        if (this.flusher != null) {
            throw new IllegalStateException("PrometheusRemoteWriteSink is already started");
        }
        final Thread thread = new ThreadFactoryBuilder()
                .setNameFormat("remote-write-flusher")
                .setDaemon(true)
                .build()
                .newThread(this::flushLoop);
        this.flusher = thread;
        thread.start();
    }

    private void flushLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            final List<Sample> batch = new ArrayList<>();
            try {
                if (this.stopped.get()) {
                    this.queue.drainTo(batch, this.config.getBatch().getMaxSamples());
                    if (batch.isEmpty()) {
                        return;
                    }
                } else {
                    try {
                        Queues.drain(this.queue, batch, this.config.getBatch().getMaxSamples(),
                                this.config.getBatch().getMaxLatency());
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!batch.isEmpty()) {
                            this.failed.inc(batch.size());
                            log.warn("Flusher interrupted with {} samples drained but unflushed", batch.size());
                        }
                        return;
                    }
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                }
            } catch (final Throwable e) {
                // Throwable on purpose: this is the only flusher, and a silent death would turn
                // into a permanent 100% drop — see BatchingFlowRepository.flushLoop.
                this.failed.inc(batch.size());
                log.error("Unexpected error in the remote-write flusher, continuing. All {} samples are"
                        + " counted as failed.", batch.size(), e);
            }
        }
    }

    private void flush(final List<Sample> batch) {
        this.batchSize.update(batch.size());
        try (var ignored = this.flushTimer.time()) {
            final byte[] body = RemoteWriteEncoder.snappy(RemoteWriteEncoder.encode(batch));
            for (int attempt = 1; ; attempt++) {
                final int status = post(body);
                if (status >= 200 && status < 300) {
                    this.sent.inc(batch.size());
                    return;
                }
                final boolean retryable = status == 429 || status >= 500 || status < 0;
                if (!retryable || attempt >= this.config.getMaxAttempts()) {
                    this.failed.inc(batch.size());
                    log.warn("Remote write of {} samples failed with status {} after {} attempt(s); batch dropped",
                            batch.size(), status, attempt);
                    return;
                }
                sleep(this.config.getRetryBackoff().multipliedBy(1L << (attempt - 1)));
            }
        }
    }

    /** Returns the HTTP status, or -1 when the request could not be made at all. */
    private int post(final byte[] body) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
            final SSLSocketFactory factory = this.trust.socketFactory();
            if (factory != null && connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(factory);
            }
            connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/x-protobuf");
            connection.setRequestProperty("Content-Encoding", "snappy");
            connection.setRequestProperty("X-Prometheus-Remote-Write-Version", "0.1.0");
            // Resolved on every flush, not cached at construction, so a rotated credential takes
            // effect on the next request rather than at the next restart.
            final String token = this.secretResolvers.resolve(this.config.getBearerToken());
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            final int status = connection.getResponseCode();
            drain(connection, status);
            return status;
        } catch (final IOException e) {
            log.debug("Remote write POST failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Reads whatever body the connection has left so it can return to the keep-alive pool.
     * {@code getInputStream()} throws on a non-2xx status, so the error body — capped, since it
     * comes from the far end of the connection this batch already failed against — is drained
     * through {@code getErrorStream()} instead.
     */
    private static void drain(final HttpURLConnection connection, final int status) throws IOException {
        if (status >= 200 && status < 300) {
            try (InputStream in = connection.getInputStream()) {
                in.readNBytes(ERROR_DRAIN_LIMIT);
            }
            return;
        }
        try (InputStream errors = connection.getErrorStream()) {
            if (errors != null) {
                errors.readNBytes(ERROR_DRAIN_LIMIT);
            }
        }
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        if (!this.stopped.compareAndSet(false, true)) {
            return;
        }
        final Thread thread = this.flusher;
        boolean graceExpired = false;
        if (thread != null) {
            try {
                thread.join(Math.max(1, this.config.getShutdownGracePeriod().toMillis()));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                graceExpired = true;
                thread.interrupt();
                log.warn("Remote-write flusher did not drain within {}; about {} accepted samples undelivered",
                        this.config.getShutdownGracePeriod(), this.queue.size());
                try {
                    thread.join(INTERRUPT_JOIN_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive()) {
                    log.warn("Remote-write flusher still alive after the interrupt — continuing shutdown");
                }
            }
            this.flusher = null;
        }

        sweep(graceExpired);

        final List<Sample> residue = new ArrayList<>();
        this.queue.drainTo(residue);
        if (!residue.isEmpty()) {
            this.dropped.inc(residue.size());
            log.warn("Dropping {} samples offered after the shutdown drain", residue.size());
        }

        this.metricRegistry.remove(this.queueDepthGauge);
    }

    private void sweep(final boolean graceExpired) {
        while (true) {
            final List<Sample> chunk = new ArrayList<>();
            this.queue.drainTo(chunk, this.config.getBatch().getMaxSamples());
            if (chunk.isEmpty()) {
                return;
            }
            if (graceExpired) {
                this.failed.inc(chunk.size());
                log.error("Dropping {} leftover samples: the shutdown grace period is exhausted", chunk.size());
            } else {
                flush(chunk);
            }
        }
    }
}
