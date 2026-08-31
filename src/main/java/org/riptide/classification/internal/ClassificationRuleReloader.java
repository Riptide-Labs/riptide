/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.ClassificationConfig;
import org.riptide.config.FileWatchTrigger;

import java.time.Duration;
import java.util.Objects;

/**
 * Opt-in hot-reload of the classification rules ({@code riptide.classification.rules}):
 * a rules edit — a local file or an {@code http(s)://} endpoint — applies without a
 * restart. Absent or zero {@code riptide.classification.reload-interval}, nothing is
 * scheduled and the rules stay exactly as they were parsed at boot.
 *
 * <p><b>Trigger</b>: {@link FileWatchTrigger}, the same loop the config and inventory
 * reloaders run — an mtime-independent content-hash poll, the source re-resolved every
 * cycle. Unchanged bytes never rebuild the decision tree; the hash decides, not the
 * clock. The source is {@link ClassificationRulesSource}, so a remote fetch is bounded
 * and an unreachable or hung endpoint ends the cycle instead of stalling the schedule.
 *
 * <p><b>Who owns which failure</b>: this class owns fetching, the engine owns loading,
 * and they share one metric family rather than publishing two that could disagree.
 * A fetch that throws is counted here on {@code classification.reload.failures} and
 * described by {@link FileWatchTrigger.Cycle#onFailure}; a fetch that succeeds is handed to
 * {@link AsyncReloadingClassificationEngine#reload()}, which counts the load's outcome on
 * that same counter and on {@code classification.reload.successes}. The two are disjoint
 * — a failed fetch never reaches the engine, and a parse failure is never seen here,
 * because the reload runs asynchronously and returns before it can fail — so nothing is
 * counted twice. The stale gauge ORs both latches for the same reason.
 *
 * <p><b>A ruleset that fails to load is attempted once</b>, deliberately, and is not
 * re-attempted until its content changes — the same posture as the two file reloaders,
 * whose {@code theSameBadContentIsAttemptedOnlyOnce} pins it. Bytes that would not parse
 * this cycle will not parse next cycle either, so a retry loop would rebuild nothing,
 * count a failure every interval and bury the first, real one. The failure stays visible
 * on {@code classification.reload.failures} and holds {@code classification.reload.stale}
 * at 1 through the engine's half until a later ruleset loads; fixing the ruleset is what
 * ends it, and the next poll picks that up as an ordinary change. This is stated in the
 * operator docs, not only here.
 *
 * <p><b>What "committed" means here</b>: the trigger's committed hash is marked once the
 * reload has been <em>submitted</em>, not once it has published, because the reload is
 * asynchronous and the schedule thread must not wait on it. So after a failed load the
 * trigger's own half of the stale gauge reads 0 — the source and the last attempt agree —
 * and it is the engine's half that holds the 1. That is what the OR is for. The
 * never-retry property above does not depend on this: {@code lastAttemptedHash} is set
 * before the hand-over, so the same bytes are never offered twice either way.
 */
@Slf4j
public class ClassificationRuleReloader {

    private final ClassificationConfig config;
    private final AsyncReloadingClassificationEngine engine;
    private final ClassificationRulesSource source;
    private final MetricRegistry metrics;

    /**
     * The engine's own counter, deliberately: a fetch failure and a load failure are
     * disjoint, so counting both here keeps one series for "a reload did not happen"
     * instead of two an operator would have to add up.
     */
    private final Counter reloadFailures;

    /** The shared poll loop: schedule, hashes, skips, failure counting, gauges. */
    private FileWatchTrigger trigger;

    public ClassificationRuleReloader(final ClassificationConfig config,
                                      final AsyncReloadingClassificationEngine engine,
                                      final ClassificationRulesSource source,
                                      final MetricRegistry metrics) {
        this.config = Objects.requireNonNull(config);
        this.engine = Objects.requireNonNull(engine);
        this.source = Objects.requireNonNull(source);
        this.metrics = Objects.requireNonNull(metrics);
        // already registered by the engine; counter() returns that same instance
        this.reloadFailures = metrics.counter(MetricRegistry.name("classification", "reload", "failures"));
    }

    @PostConstruct
    void start() {
        final Duration interval = this.config.getReloadInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.debug("Classification rule hot-reload disabled (no riptide.classification.reload-interval)");
            return;
        }
        // seeded, unlike the config reloader: the engine has already published the rules
        // this source held at boot, so an unseeded first cycle would rebuild the decision
        // tree from bytes that are already serving
        this.trigger = new FileWatchTrigger(log, this.source, interval, "ClassificationRuleReloader",
                messages(this.source.describe()), this.metrics, "classification",
                this.reloadFailures, true, new FileWatchTrigger.Cycle() {
                    @Override
                    public void onContent(final byte[] content) {
                        // the bytes are not handed over: the engine re-reads through the
                        // same bounded source, because reload() is the only path that
                        // publishes atomically and reports its own outcome. So a CHANGED
                        // cycle costs two fetches, this one and the engine's; an unchanged
                        // cycle costs one. A change landing between the two reads is seen
                        // by the next cycle, whose hash no longer matches what was
                        // attempted here
                        log.info("Classification rules at {} changed ({} bytes) — reloading; the engine logs "
                                + "the ruleset it publishes and counts the outcome on classification.reload.*",
                                source.describe(), content.length);
                        engine.reload();
                        trigger.markCommitted();
                    }

                    @Override
                    public void onIdle() {
                        // nothing is ever left pending here: a fetched candidate is either
                        // handed to the engine or skipped, and the engine retries nothing
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        log.warn("Fetching the classification rules from {} failed — keeping the last good rules "
                                + "serving; classification.reload.stale stays 1 until a fetch succeeds: {}",
                                source.describe(), e.getMessage(), e);
                    }
                });
        // owner-supplied, not the trigger's flag read directly: the engine latches a load
        // that failed after a fetch this trigger considers perfectly healthy
        this.trigger.start(() -> this.trigger.isStale() || this.engine.isStale() ? 1 : 0);
        log.info("Classification rule hot-reload enabled: watching {} every {}", this.source.describe(), interval);
    }

    /** The skip and shutdown sentences, spelled for a source that can be a URL as easily as a file. */
    private static FileWatchTrigger.Messages messages(final String location) {
        return new FileWatchTrigger.Messages(
                "Classification rule reload poll skipped: thread interrupted (shutdown)",
                "Classification rule reload poll interrupted mid-cycle (shutdown): {}",
                ("Classification rules %s are not there (a deleted file, or a 404) — skipping reload cycles "
                        + "until they reappear; the last good rules keep classifying").formatted(location),
                ("Classification rules %s are empty or whitespace-only — skipping reload cycle "
                        + "(truncate-write race, or an endpoint answering with no body; the last good rules "
                        + "keep classifying)").formatted(location),
                "Classification rule reload housekeeping failed unexpectedly; the reload schedule keeps running: {}");
    }

    @PreDestroy
    void stop() {
        if (this.trigger != null) {
            this.trigger.stop();
        }
    }

    // a test-only seam: nothing here schedules this, the trigger schedules its own poll.
    // (The two file reloaders carry a "visible for the scheduled task" comment on their
    // equivalent, left over from before the trigger owned the schedule; it is not copied
    // to a third site.) Never throws, because the trigger's poll() does not
    void poll() {
        if (this.trigger != null) {
            this.trigger.poll();
        }
    }
}
