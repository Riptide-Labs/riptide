/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PreDestroy;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * A classification engine that does reloads asynchronously.
 * <p>
 * Reloads are triggered oftentimes while editing classification rules. In addition, reloads may take a couple of
 * seconds depending on the enabled rules. In order to keep the front-end responsive, reloads are done asynchronously.
 * <p>
 * <b>Failure semantics</b>: the discriminator is whether a load has ever succeeded, not what the last reload did.
 * Once rules are loaded, no caller blocks on a reload and no caller is failed by one: the previously loaded rules
 * keep classifying, the failure is counted on {@code classification.reload.failures} and held visible by the
 * {@code classification.reload.stale} gauge until a later reload succeeds. (Callers still serialize against each
 * other — {@link #classify} and {@link #getInvalidRules} are {@code synchronized} — but that lock is never held
 * across a rebuild.) Before the first successful load there is nothing to serve, so callers block until the initial
 * load settles and throw if it fails.
 * <p>
 * <b>Shutdown is not a failure</b>: an interrupted or rejected reload during an orderly stop moves no counter and
 * latches no gauge, matching the contract the {@code config.reload.*} and {@code inventory.reload.*} families
 * publish.
 */
public class AsyncReloadingClassificationEngine implements ClassificationEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncReloadingClassificationEngine.class);

    private enum State {
        READY, RELOADING, FAILED
    }

    private final ClassificationEngine delegate;

    // uses at most one additional thread; if the thread is not used for 60 seconds then it is terminated
    // -> uses no additional resources while being idle
    private final ExecutorService executorService = new ThreadPoolExecutor(0, 1,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), // multiple reloads may have been enqueued and cancelled
            runnable -> new Thread(runnable, "AsyncReloadingClassificationEngine")
    );

    private final Counter reloadSuccesses;
    private final Counter reloadFailures;

    /**
     * Set once, on the first successful reload, and never cleared: a later failure does not un-load the rules that
     * are still in memory. This is what separates "a reload failed" from "no rules are serviceable" — the state
     * below only says what the last reload attempt did.
     */
    private boolean everLoaded = false;

    /**
     * Latched by a failure, cleared only by a success — deliberately not derived from {@link #state}. A gauge reading
     * {@code state == FAILED} would drop back to 0 the moment the next reload is submitted, so an operator whose
     * rules never recovered would see 1 for at most one retry while stale rules kept serving.
     */
    private volatile boolean stale = false;

    /**
     * Ends the pre-first-load wait when the engine is stopped: {@link #shutdown()} drains the queue and interrupts
     * the running load, so nothing will ever settle the state, and an untimed {@code wait()} would park every caller
     * for the rest of the process. Not consulted once {@link #everLoaded} is true — classification keeps working off
     * the loaded rules long after the reload executor is gone.
     */
    private boolean shuttingDown = false;

    private State state = State.READY;
    private Throwable reloadException;
    private Future<?> reloadFuture;

    /**
     * @param delegate the engine to reload and to classify against. <b>It must publish a rebuilt ruleset
     *     atomically</b>: whatever was serving has to keep serving, complete, until a rebuild has fully succeeded,
     *     and a failed rebuild must leave it untouched. That property is what lets this wrapper answer from the
     *     previously loaded rules instead of failing the caller, and it is not part of the
     *     {@link ClassificationEngine} contract, so a new delegate has to provide it deliberately.
     *     {@link DefaultClassificationEngine} does (one {@code AtomicReference} set on success), and
     *     {@link TimingClassificationEngine} passes it through.
     * @param metrics registry for {@code classification.reload.{successes,failures,stale}} and
     *     {@code classification.rules.{rejected,published}}; must be the registry the process exports, or the
     *     reported failure is invisible and the alert the docs prescribe has no series
     */
    public AsyncReloadingClassificationEngine(ClassificationEngine delegate, MetricRegistry metrics) {
        this.delegate = Objects.requireNonNull(delegate);
        Objects.requireNonNull(metrics);
        this.reloadSuccesses = metrics.counter(MetricRegistry.name("classification", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("classification", "reload", "failures"));
        // registered unconditionally, unlike the file reloaders' gauges (#539): those claim a relationship between
        // disk and what is serving, so a constant 0 would falsely read "in sync". This one claims only "the last
        // reload attempt failed and has not recovered", and 0 is simply true when no reload has run.
        // Remove-then-register, not Dropwizard's get-or-create: get-or-create would hand a restarted bean
        // (devtools, cached test contexts) the OLD instance's lambda, permanently reading dead fields.
        // With a reload interval configured, ClassificationRuleReloader re-registers this same name over a gauge
        // that ORs {@link #isStale()} with its own fetch latch, so the one series covers both halves (#655)
        final String staleName = MetricRegistry.name("classification", "reload", "stale");
        metrics.remove(staleName);
        metrics.register(staleName, (Gauge<Integer>) () -> this.stale ? 1 : 0);
        // #765: a rejected rule is deliberately NOT a failed reload — the rest of the ruleset serves,
        // successes moves and stale stays 0 — so without these two, every metric reads healthy while
        // part of an operator's edit classifies nothing. That WARN was the only signal, and a log line
        // is not something you can alert on. Alert on rejected > 0.
        //
        // Read through currentPublication(), never getInvalidRules(): a gauge is evaluated by whatever
        // scrapes the metrics, and getInvalidRules() waits for the initial load to settle, so it would
        // park a scrape behind a reload — or throw outright when no load has ever succeeded.
        // currentPublication() is a single reference read that never blocks and never throws.
        //
        // -1, not 0, when nothing has been published: that is the distinction the Optional on
        // currentPublication() exists for. On a failed INITIAL load there is no publication at all and
        // the collector classifies nothing, which is exactly when a 0 here would claim a ruleset that
        // loaded cleanly. Registered unconditionally, like stale above and unlike the file reloaders'
        // gauges, because a rejected rule is reported at boot whether or not a schedule is configured.
        registerRuleGauge(metrics, "rejected", publication -> publication.invalidRules().size());
        registerRuleGauge(metrics, "published", publication -> publication.rules().size());
        // trigger reload
        // -> blocks classification requests until the first load settles
        reload();
    }

    /**
     * Registers one {@code classification.rules.*} gauge, reading the current publication and
     * answering {@code -1} when there is none. Remove-then-register for the same reason as the stale
     * gauge above: Dropwizard's get-or-create would hand a restarted bean the old instance's lambda,
     * which would then read a dead delegate forever.
     *
     * @param measure what to count on a publication that exists; never called when there is none
     */
    private void registerRuleGauge(final MetricRegistry metrics,
                                   final String name,
                                   final ToIntFunction<Publication> measure) {
        final String metricName = MetricRegistry.name("classification", "rules", name);
        metrics.remove(metricName);
        metrics.register(metricName, (Gauge<Integer>) () ->
                currentPublication().map(measure::applyAsInt).orElse(-1));
    }

    @PreDestroy
    public synchronized void shutdown() {
        executorService.shutdownNow();
        // shutdownNow drains the queue and interrupts the running load, so no state transition is coming. Waking
        // the waiters here rather than from the interrupt handler covers the queued-but-never-started load too,
        // which is drained without ever raising an InterruptedException
        shuttingDown = true;
        notifyAll();
    }

    private void setState(State newState) {
        state = newState;
        notifyAll();
    }

    /**
     * Returns as soon as there are rules to classify against. Once {@link #everLoaded} is true that is immediate,
     * whatever a reload is currently doing; before it, the caller blocks until the initial load settles and throws
     * if it failed or if the engine was stopped first.
     */
    private void waitUntilServiceable() {
        while (true) {
            if (everLoaded) {
                // rules are in memory and the delegate serves them atomically: a running or
                // failed reload is not this caller's problem
                return;
            }
            if (shuttingDown) {
                throw new RuntimeException(
                        "classification engine was shut down before any rules were loaded", reloadException);
            }
            switch (state) {
                case READY -> {
                    return;
                }
                case FAILED -> throw new RuntimeException(
                        "classification engine can not be used because no rules have ever been loaded: the initial load failed",
                        reloadException);
                case RELOADING -> {
                    // fall through to wait() below until the initial load settles
                }
            }
            try {
                wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void doReload() {
        // this method must not modify the state because it not synchronized
        try {
            LOG.debug("reload classification engine");
            delegate.reload();
            LOG.debug("classification engine reloaded");
            onReloadSucceeded();
        } catch (InterruptedException e) {
            // restore the flag, as every other shutdown path in this codebase does: the pool clears it before the
            // next task, but a stop in progress above us must still be able to see it
            Thread.currentThread().interrupt();
            LOG.debug("reload was interrupted");
            // the state is deliberately left alone: a superseded reload has its replacement already queued, and a
            // shutdown is woken by shutdown() itself. Neither is a failure, so no counter moves and nothing latches
        } catch (Throwable e) {
            onReloadFailed(e);
        }
    }

    private synchronized void onReloadSucceeded() {
        everLoaded = true;
        stale = false;
        setState(State.READY);
        reloadSuccesses.inc();
    }

    private synchronized void onReloadFailed(Throwable e) {
        reloadException = e;
        stale = true;
        if (everLoaded) {
            // the trade this class makes: a loud failure (every flow throwing) becomes a quiet one
            // (the previous rules keep classifying), so the quiet one has to say so out loud
            LOG.warn("Reload of the classification rules failed — keeping the last good rules serving; "
                    + "classification.reload.stale stays 1 until a reload succeeds: {}", e.getMessage(), e);
        } else {
            LOG.error("Initial load of the classification rules failed — classification is unavailable "
                    + "until a reload succeeds", e);
        }
        setState(State.FAILED);
        // counted last, after the gauge and the log: the registry renders alphabetically, so a scrape that sees
        // `failures` move must already see `stale` at 1. Incrementing first published a moment where an operator
        // alerting on the counter could read the gauge as 0 in the very same scrape
        reloadFailures.inc();
    }

    /**
     * Whether the last reload attempt failed and none has succeeded since — the value behind
     * {@code classification.reload.stale}. Read by {@link ClassificationRuleReloader}, which ORs it with its own
     * fetch latch: a source it could not fetch never reaches a reload here, so neither half sees the other's
     * failure and one gauge has to carry both.
     */
    public boolean isStale() {
        return this.stale;
    }

    @Override
    public synchronized String classify(ClassificationRequest classificationRequest) {
        waitUntilServiceable();
        return delegate.classify(classificationRequest);
    }

    @Override
    public synchronized List<Rule> getInvalidRules() {
        waitUntilServiceable();
        return delegate.getInvalidRules();
    }

    /**
     * Deliberately neither {@code synchronized} nor guarded by {@link #waitUntilServiceable()}, unlike every other
     * accessor on this class. A listener's callback runs on the reload thread, <em>inside</em> {@code doReload}, so
     * on the initial load it reaches this object with {@link #everLoaded} still false and the state still
     * {@code RELOADING} — and the transition it would be waiting for can only be made by the very thread that is
     * waiting. Waiting here is therefore a self-deadlock, not a delay, and the monitor would add a second way to
     * park behind a reload that is already parked on a callback.
     */
    @Override
    public Optional<Publication> currentPublication() {
        return delegate.currentPublication();
    }

    @Override
    public synchronized void reload() {
        switch (state) {
            case READY, FAILED -> {
                if (submitReload()) {
                    setState(State.RELOADING);
                }
            }
            case RELOADING -> {
                reloadFuture.cancel(true);
                // if the resubmit is refused the state stays RELOADING with reloadFuture pointing at the future we
                // just cancelled. That is deliberate: re-cancelling a cancelled future is a no-op, whereas nulling
                // it would hand the next visit to this arm a NullPointerException. The only way here is a shutdown,
                // where shutdown() has already woken anyone waiting
                submitReload();
            }
        }
    }

    /**
     * Submits a reload, reporting a refusal instead of throwing it at the caller; true when one is now in flight.
     * Both arms of {@link #reload()} go through here — the guard used to exist on one of them only, so a rejected
     * resubmit escaped to the caller of the arm that lacked it.
     */
    private boolean submitReload() {
        try {
            reloadFuture = executorService.submit(this::doReload);
            return true;
        } catch (final RejectedExecutionException e) {
            if (executorService.isShutdown()) {
                // an orderly stop is not a rules failure: no counter moves and no stale latch is set
                LOG.debug("Reload of the classification rules was not submitted: the engine is shutting down");
            } else {
                onReloadFailed(e);
            }
            return false;
        }
    }

    @Override
    public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.delegate.addClassificationRulesReloadedListener(classificationRulesReloadedListener);
    }

    @Override
    public void removeClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.delegate.removeClassificationRulesReloadedListener(classificationRulesReloadedListener);
    }
}
