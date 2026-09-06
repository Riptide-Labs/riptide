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
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.Rule;
import org.riptide.config.ClassificationConfig;
import org.riptide.config.FileWatchTrigger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 * <p><b>What a reload published</b> is reported here too, from the listener seam
 * ({@link org.riptide.classification.ClassificationEngine#addClassificationRulesReloadedListener}),
 * not from the counters: a ruleset whose rules the engine could not all use is a
 * <em>success</em> — it publishes, the counter moves and the gauge stays 0 — so nothing in
 * <em>this</em> metric family says that half the operator's edit is classifying nothing. The
 * {@code classification.rules.rejected} gauge does, registered by the engine rather than here
 * (#765); this log line remains the only place that says <em>which</em> rules and why. See
 * {@link #logPublication}.
 *
 * <p><b>Registering is not enough</b>: the engine's constructor submits the boot load before
 * this bean exists, so by the time {@link #start()} runs that load has usually published
 * already and no callback is coming — the seam replays nothing to a late registrant, by
 * design. So {@link #start()} registers and then <em>asks</em>
 * ({@link org.riptide.classification.ClassificationEngine#currentPublication()}). Whichever of
 * the two paths reaches a publication first reports it and the other finds it claimed, so the
 * boot publish is logged exactly once whether the callback wins the race or the pull does.
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

    /** How many rejected rules the publish WARN names before it summarises the rest. */
    private static final int MAX_REJECTED_RULES_NAMED = 20;

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

    /**
     * Registered on the engine for the reloader's lifetime, and the reason the seam in
     * {@link org.riptide.classification.ClassificationEngine} exists (#685). Kept as a field so
     * {@link #stop()} can take it off again — a stopped reloader reports nothing more, which is
     * what {@code aStoppedReloaderReportsNothingMore} pins. The engine outlives this bean in a
     * context that restarts only part of itself, so deregistering is not merely tidy.
     *
     * <p>The callback argument is deliberately ignored. The seam hands over the rules alone, and
     * every line this class writes needs the rejected ones too, so both the callback and the pull
     * in {@link #start()} go through the same single read of
     * {@link ClassificationEngine#currentPublication()} rather than describing one publication
     * from two different sources.</p>
     */
    private final ClassificationEngine.ClassificationRulesReloadedListener publishedRulesLogger =
            rules -> reportCurrentPublication();

    /**
     * The last publication reported, so the boot publish is reported exactly once no matter which
     * of the two paths reaches it first.
     *
     * <p>Compared by <b>identity</b>, not by {@code equals}: every reload constructs a fresh
     * {@code Publication}, so identity means "this same publish", whereas record equality would
     * silence a genuine later reload that happened to publish a byte-identical ruleset.</p>
     *
     * <p>This is not replay by another name. Nothing is re-delivered and the engine remembers no
     * listener: the consumer asks once, on its own thread, and this reference only decides whether
     * the answer has already been written to the log.</p>
     */
    private final AtomicReference<ClassificationEngine.Publication> reported = new AtomicReference<>();

    @PostConstruct
    void start() {
        // above the interval gate: a reload can be requested without a schedule, and what a
        // reload published is worth reporting either way. A disabled reloader simply never
        // sees one, because nothing here triggers it
        this.engine.addClassificationRulesReloadedListener(this.publishedRulesLogger);
        // ...and then ask, because registering is not enough. The engine's constructor submits the
        // boot load before this bean exists, so by now that load has usually already published and
        // its callback is gone for good — replay is deliberately not on offer (#685), so the only
        // way a late registrant learns what is serving is to ask. compareAndSet, not a plain read:
        // if the callback got there first the pull must add nothing, and if the publish lands
        // between the registration above and this line both paths see it and exactly one wins
        final Optional<ClassificationEngine.Publication> bootPublication = this.engine.currentPublication();
        if (bootPublication.isPresent() && this.reported.compareAndSet(null, bootPublication.get())) {
            logPublication(bootPublication.get());
        }
        final Duration interval = this.config.getReloadInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.debug("Classification rule hot-reload disabled (no riptide.classification.reload-interval)");
            return;
        }
        // seeded, unlike the config reloader: the engine has already published the rules
        // this source held at boot, so an unseeded first cycle would reload the engine from
        // bytes that are already serving — a pointless publish, a pointless listener fan-out
        // and a second fetch. Since #707 that cycle would hit the decision-tree cache rather
        // than rebuild, so the cost it avoids is no longer a tree build; the reason to seed is
        // that the work is redundant, not that it is expensive
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
                        log.info("Classification rules at {} changed ({} bytes) — reloading; the reload is "
                                + "asynchronous, so what was published is logged when it lands and the "
                                + "outcome is counted on classification.reload.*",
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

    /**
     * The listener half of the report: describes whatever is published now, unless the pull in
     * {@link #start()} already described that same publication.
     *
     * <p>Runs on the reload thread, inside {@code reload()}, so it reads
     * {@link ClassificationEngine#currentPublication()} — the accessor that does not wait — rather
     * than {@code getInvalidRules()}, which on the initial load would park this thread waiting for
     * itself. A throw from here cannot fail the reload: {@code DefaultClassificationEngine} isolates
     * each listener, which is where that guarantee lives rather than in a catch block per consumer.</p>
     */
    // identity is the point, not an oversight: "the same publish", not "a ruleset that compares equal".
    // Record equality would silence a genuine later reload that republished a byte-identical ruleset
    @SuppressWarnings("ReferenceEquality")
    private void reportCurrentPublication() {
        final ClassificationEngine.Publication publication = this.engine.currentPublication().orElse(null);
        // getAndSet, so the loser of a race with the pull is whichever arrives second rather than
        // whichever is on which thread. A null publication cannot happen from inside a callback —
        // the publish precedes the fire — and is skipped rather than reported as an empty ruleset
        if (publication == null || this.reported.getAndSet(publication) == publication) {
            return;
        }
        logPublication(publication);
    }

    /**
     * What a reload actually published, written once the publish has landed — the outcome the
     * pre-reload INFO above can only promise, because the reload is asynchronous and that line is
     * written before the ruleset has been read, let alone accepted.
     *
     * <p>The rejected rules are the half nothing else reports in one place: the engine logs each
     * one as it is dropped, mid-rebuild and interleaved with the tree statistics, so an operator
     * reading the reload's own lines cannot tell whether the ruleset that is now serving is the
     * one they edited. This says how many of their rules are classifying nothing, and names them.</p>
     *
     * <p>The location comes from {@link ClassificationRulesSource#describe()}, which is the one place
     * applying the userinfo redaction the operator docs promise — never from the raw resource. It is
     * the same source the engine's provider reads through, which is what makes naming it here a
     * statement about these rules rather than about an unrelated file that happens to be configured.</p>
     */
    private void logPublication(final ClassificationEngine.Publication publication) {
        final List<Rule> rejected = publication.invalidRules();
        if (rejected.isEmpty()) {
            log.info("Classification rules from {} published: {} rules, none rejected",
                    this.source.describe(), publication.rules().size());
        } else if (log.isWarnEnabled()) {
            // guarded, unlike the INFO above: naming the rejected rules builds a string, and a ruleset
            // whose every rule fails preprocessing would build it once per reload for a disabled level
            log.warn("Classification rules from {} published: {} rules, of which {} were rejected and "
                            + "classify nothing: {}",
                    this.source.describe(), publication.rules().size(), rejected.size(),
                    describeRejected(rejected));
        }
    }

    /**
     * The rejected rules' names, capped. A ruleset that fails wholesale — a column reordered, a
     * delimiter changed — rejects every rule it has, and this line would otherwise carry thousands of
     * names on one physical line, at boot and again on every reload. The count above is the number
     * that matters; the names are here to start the operator off, not to be exhaustive.
     */
    private static String describeRejected(final List<Rule> rejected) {
        final String names = rejected.stream()
                .limit(MAX_REJECTED_RULES_NAMED)
                .map(Rule::getName)
                .collect(Collectors.joining(", "));
        final int remaining = rejected.size() - MAX_REJECTED_RULES_NAMED;
        return remaining > 0 ? names + ", and %d more".formatted(remaining) : names;
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
        this.engine.removeClassificationRulesReloadedListener(this.publishedRulesLogger);
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
