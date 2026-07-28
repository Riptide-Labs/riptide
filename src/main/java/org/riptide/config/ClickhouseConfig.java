/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "riptide.clickhouse")
public final class ClickhouseConfig {
        private String endpoint = "http://localhost:8123";

        /**
         * ClickHouse credentials as {@link SecretRef}s: a bare literal binds through the plain
         * fallback (existing configs keep working), while a {@code scheme://…} reference is
         * resolved from a secret store at repository construction. Left unset — or explicitly
         * blank (e.g. {@code riptide.clickhouse.password=}) — binds null for the default user /
         * empty password. Per-tenant writer credentials are sourced this way, with no plaintext in
         * configuration.
         */
        private SecretRef username;
        private SecretRef password;

        private String database = "riptide";

        /**
         * When {@code true} (default), riptide ensures the ClickHouse schema idempotently at
         * startup. When {@code false}, riptide creates nothing and instead validates that an
         * admin-provisioned {@code flows} table is present, failing fast if it is not — the
         * multi-tenant / provisioned mode.
         */
        private boolean manageSchema = true;

        /**
         * Server-side insert coalescing ({@code async_insert}). Historically the pipeline
         * inserted once per flow record, and each insert also feeds the rollup materialized
         * views — without coalescing, that many small inserts collapse ingestion throughput on
         * modest hardware (measured 206 → 56 inserts/s with the four rollups on two cores;
         * coalescing brought it to 607). That role is superseded by the client-side batching
         * buffer (see {@link BatchConfig}): the collector now hands the server one large insert
         * per batch, which server-side coalescing cannot improve on. Coalescing also costs
         * insert-error visibility: the insert is acknowledged when buffered
         * ({@code wait_for_async_insert=0}), so a row the server later rejects — notably a
         * mis-tenanted row failing the multi-tenant CHECK barrier — is dropped without the
         * collector seeing an error.
         *
         * <p>Note the supersession changes who sees an error, not just throughput: with batching
         * enabled, insert failures surface as flusher error logs plus the
         * {@code persister.batch.failedRows} counter — not as synchronous exceptions to the
         * caller. The synchronous rejection signal only exists with batching disabled and
         * coalescing off.
         *
         * <p>Unset (default) is off while batching is enabled. With batching disabled it falls
         * back to the pre-batching derived default — on exactly in manage mode (single-tenant,
         * lossy UDP transport), off in provisioned mode (synchronous CHECK-barrier rejection is
         * part of the isolation contract) — because otherwise a {@code batch.enabled=false}
         * config would silently land on the measured-slowest combination (56 inserts/s). Set
         * explicitly to override either way.
         */
        private Boolean asyncInserts;

        /**
         * The effective setting: the explicit value if set; unset is off under batching (which
         * supersedes coalescing), otherwise the pre-batching derived default (on exactly in
         * manage mode).
         */
        public boolean isAsyncInserts() {
                if (this.asyncInserts != null) {
                        return this.asyncInserts;
                }
                return !this.batch.isEnabled() && this.manageSchema;
        }

        private BatchConfig batch = new BatchConfig();

        /**
         * Client-side insert batching: a bounded queue in front of the repository, drained by a
         * single background flusher into one insert per batch. Each insert forms a part and fires
         * the four rollup materialized views, so many small inserts collapse throughput — the
         * per-record path capped a 4-vCPU host at ~150 inserts/s ≈ 3,600 rows/s with the CPU
         * mostly idle. ClickHouse guidance is 10k–100k rows per insert at roughly one insert per
         * second; the defaults below sit at the low end of that.
         */
        @Data
        public static final class BatchConfig {
                /** Off falls back to the per-record insert path (one insert per persist call). */
                private boolean enabled = true;

                /**
                 * Flush once this many rows are buffered, even if {@link #maxLatency} has not
                 * elapsed. 10k is the low end of the ClickHouse guidance — large enough to
                 * amortize part formation and rollup fan-out, small enough to keep a batch's
                 * heap footprint modest.
                 */
                private int maxRows = 10_000;

                /**
                 * Flush whatever is buffered after this long, even below {@link #maxRows} —
                 * bounds how stale dashboards go at low flow rates. 2 s matches the ~1 insert/s
                 * guidance (issue #382's 200 ms would undersize batches below ClickHouse's
                 * 1,000-row floor). Must stay well below {@link #shutdownGracePeriod}: the
                 * flusher notices the stop signal only between drain windows.
                 */
                private Duration maxLatency = Duration.ofSeconds(2);

                /**
                 * Bound of the buffer between producers and the flusher: 40k = four full
                 * batches, enough to ride out one slow insert. When full, producers drop flows
                 * (counted + logged) instead of blocking — ClickHouse latency otherwise
                 * backpressures the parser executors into the Netty socket, where the loss is
                 * invisible.
                 */
                private int queueCapacity = 40_000;

                /**
                 * How long {@code stop()} waits for the flusher to drain accepted rows before
                 * giving up. Keep this below the service manager's stop timeout (systemd
                 * {@code TimeoutStopSec}), or the process is killed mid-drain — and note the
                 * listeners stop first, each waiting up to ~5 s for its parser executor, before
                 * this grace period even starts.
                 */
                private Duration shutdownGracePeriod = Duration.ofSeconds(5);

                /**
                 * Fail fast on values that would misbehave at runtime; called when the batching
                 * repository is constructed. {@code maxRows <= 0} would busy-spin the flusher,
                 * {@code queueCapacity <= 0} only surfaces as an opaque queue exception, and
                 * {@code maxLatency >= shutdownGracePeriod} makes the shutdown drain miss its
                 * grace window (the flusher notices the stop signal only between drain windows).
                 */
                public void validate() {
                        if (this.maxRows <= 0) {
                                throw new IllegalArgumentException(
                                        "riptide.clickhouse.batch.max-rows must be > 0 (got " + this.maxRows + ")");
                        }
                        if (this.queueCapacity <= 0) {
                                throw new IllegalArgumentException(
                                        "riptide.clickhouse.batch.queue-capacity must be > 0 (got " + this.queueCapacity + ")");
                        }
                        if (this.maxLatency == null || this.maxLatency.isZero() || this.maxLatency.isNegative()) {
                                throw new IllegalArgumentException(
                                        "riptide.clickhouse.batch.max-latency must be positive (got " + this.maxLatency + ")");
                        }
                        if (this.shutdownGracePeriod == null || this.shutdownGracePeriod.isZero()
                                        || this.shutdownGracePeriod.isNegative()) {
                                throw new IllegalArgumentException(
                                        "riptide.clickhouse.batch.shutdown-grace-period must be positive (got "
                                                + this.shutdownGracePeriod + ")");
                        }
                        if (this.maxLatency.compareTo(this.shutdownGracePeriod) >= 0) {
                                throw new IllegalArgumentException(
                                        "riptide.clickhouse.batch.max-latency (" + this.maxLatency
                                                + ") must be shorter than shutdown-grace-period ("
                                                + this.shutdownGracePeriod
                                                + ") — the flusher notices the stop signal only between drain windows");
                        }
                }
        }
}
