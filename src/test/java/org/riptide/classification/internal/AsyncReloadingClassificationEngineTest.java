/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the whole reload posture of the wrapper, including the parts this change deliberately leaves alone.
 * <p>
 * The two rows that are the fix itself are {@link #reloadFailureAfterASuccessfulLoadKeepsTheLastGoodRulesServing}
 * (a post-load failure must not reach a caller) and {@link #reloadInFlightAfterASuccessfulLoadDoesNotBlockCallers}
 * (a post-load reload must not park a caller). The remaining rows pin the startup path, cancellation, shutdown and
 * the operator-facing signals, none of which had any coverage before.
 * <p>
 * The class-level timeout is not decoration: several of these properties fail by hanging, and the alternative to a
 * timeout is a suite that never finishes.
 */
@Timeout(30)
class AsyncReloadingClassificationEngineTest {

    private static final String STALE_GAUGE = MetricRegistry.name("classification", "reload", "stale");
    private static final String FAILURE_COUNTER = MetricRegistry.name("classification", "reload", "failures");
    private static final String SUCCESS_COUNTER = MetricRegistry.name("classification", "reload", "successes");

    private final MetricRegistry metrics = new MetricRegistry();
    private final ControllableEngine delegate = new ControllableEngine();

    private AsyncReloadingClassificationEngine engine;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        this.logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(AsyncReloadingClassificationEngine.class);
        // the level is deliberately left alone: the two assertions below read WARN and ERROR, which the
        // inherited level already passes, and lowering it here leaked DEBUG into every later test class
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() {
        if (this.engine != null) {
            this.engine.shutdown();
        }
        this.logger.detachAppender(this.appender);
        this.appender.stop();
    }

    @Test
    void reloadFailureAfterASuccessfulLoadKeepsTheLastGoodRulesServing() throws Exception {
        givenRulesLoaded("rules-1");

        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine.reload();
        await("the failed reload to settle", () -> failures() == 1);

        // the whole point: the previous rules answer, and no call throws
        assertThat(this.engine.classify(request())).isEqualTo("rules-1");
        // keyed to what the delegate is serving, so this cannot pass on a wrapper that returned nothing
        assertThat(this.engine.getInvalidRules()).extracting(Rule::getName).containsExactly("rules-1-invalid");
        assertThat(stale()).isEqualTo(1);
    }

    @Test
    void reloadInFlightAfterASuccessfulLoadDoesNotBlockCallers() throws Exception {
        givenRulesLoaded("rules-1");

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            entered.countDown();
            release.await();
        };
        this.engine.reload();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the reload started").isTrue();

        // off the test thread, so a regression fails on the timeout instead of hanging the suite.
        // The reload is still parked on `release`, so anything that waits for it cannot answer.
        final CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(() -> this.engine.classify(request()));
        assertThat(answer.get(10, TimeUnit.SECONDS)).isEqualTo("rules-1");
        assertThat(release.getCount()).as("the reload was still in flight").isEqualTo(1);

        release.countDown();
    }

    @Test
    void aSuccessfulReloadAfterAFailureClearsStalenessAndServesTheNewRules() throws Exception {
        givenRulesLoaded("rules-1");

        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine.reload();
        await("the failed reload to settle", () -> failures() == 1);
        assertThat(stale()).isEqualTo(1);

        this.delegate.onReload = () -> { };
        this.delegate.nextRules = "rules-2";
        this.engine.reload();
        // on the gauge, not on the classification: doReload publishes the new tree inside
        // delegate.reload() and only then enters onReloadSucceeded, which clears stale. A wait
        // on classify() therefore returns while stale is still 1, and this failed on CI under
        // load while passing in isolation.
        //
        // Valid here, and NOT in aSupersededReloadIsCancelledAndTheNewestWins below, for one
        // reason: the failed reload above latched the gauge to 1 — asserted on the line before —
        // so this condition is false when the wait begins. Where nothing has failed, stale is
        // already 0 and the identical line waits for nothing at all (#699).
        await("the recovering reload to clear staleness", () -> stale() == 0);

        assertThat(this.engine.classify(request()))
                .as("and the cleared gauge means the new rules are the ones serving")
                .isEqualTo("rules-2");
        assertThat(failures()).as("the recovery does not un-count the failure").isEqualTo(1);
    }

    @Test
    void consecutiveFailuresAreEachCounted() throws Exception {
        givenRulesLoaded("rules-1");

        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine.reload();
        await("the first failed reload to settle", () -> failures() == 1);
        this.engine.reload();
        // a counter, not a latch like the gauge: an operator watching the rate has to see the second failure
        await("the second failed reload to settle", () -> failures() == 2);

        assertThat(stale()).isEqualTo(1);
        assertThat(this.engine.classify(request())).isEqualTo("rules-1");
    }

    @Test
    void everySuccessfulReloadIsCounted() throws Exception {
        givenRulesLoaded("rules-1");
        // the construction-time load is a reload like any other, and it succeeded
        assertThat(successes()).isEqualTo(1);

        this.delegate.nextRules = "rules-2";
        this.engine.reload();
        // the counter is incremented after the publish, so waiting on the classification
        // would return before it moves — the same window as the recovery test above
        await("the second reload to be counted", () -> successes() == 2);

        assertThat(this.engine.classify(request())).isEqualTo("rules-2");
        assertThat(successes()).isEqualTo(2);
        assertThat(failures()).isZero();
    }

    @Test
    void whenNoLoadEverSucceededAFailedFirstLoadMakesCallersThrowNamingTheCause() throws Exception {
        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine = new AsyncReloadingClassificationEngine(this.delegate, this.metrics);
        await("the failed first load to settle", () -> failures() == 1);

        assertThatThrownBy(() -> this.engine.classify(request()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no rules have ever been loaded")
                // the cause itself, not a message that merely echoes it
                .cause().isInstanceOf(IllegalStateException.class)
                .hasMessage("rules file is unreadable");
        assertThatThrownBy(this.engine::getInvalidRules)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no rules have ever been loaded");
        assertThat(stale()).isEqualTo(1);
    }

    @Test
    void aFailureWithNoRulesEverLoadedIsLoggedAtError() throws Exception {
        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine = new AsyncReloadingClassificationEngine(this.delegate, this.metrics);
        // wait for the event itself, not for the counter: they are two different signals, and a test that
        // synchronizes on one while asserting the other is only as ordered as today's code happens to be
        await("the failure to be logged", () -> !eventsAt(Level.ERROR).isEmpty());

        // nothing is serving: this one really is an outage, and it must not be softened to a warning
        assertThat(eventsAt(Level.ERROR))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Initial load of the classification rules failed"));
        assertThat(eventsAt(Level.WARN)).isEmpty();
    }

    @Test
    void aFailureWithRulesAlreadyServingIsLoggedAtWarnNamingTheCause() throws Exception {
        givenRulesLoaded("rules-1");

        this.delegate.onReload = () -> {
            throw new IllegalStateException("rules file is unreadable");
        };
        this.engine.reload();
        await("the failure to be logged", () -> !eventsAt(Level.WARN).isEmpty());

        // the quiet failure has to say so out loud, and name why the operator is not seeing errors
        assertThat(eventsAt(Level.WARN))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("keeping the last good rules serving")
                        .contains("rules file is unreadable"));
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void whenNoLoadEverSucceededCallersBlockUntilTheFirstLoadSettles() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            entered.countDown();
            release.await();
        };
        this.engine = new AsyncReloadingClassificationEngine(this.delegate, this.metrics);
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the first load started").isTrue();

        final CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(() -> this.engine.classify(request()));
        assertThatThrownBy(() -> answer.get(500, TimeUnit.MILLISECONDS))
                .as("nothing is serviceable yet, so the caller waits")
                .isInstanceOf(TimeoutException.class);

        release.countDown();
        assertThat(answer.get(10, TimeUnit.SECONDS)).isEqualTo("rules-1");
    }

    @Test
    void anInterruptedInitialLoadDoesNotParkCallersForever() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleased = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            entered.countDown();
            neverReleased.await();
        };
        this.engine = new AsyncReloadingClassificationEngine(this.delegate, this.metrics);
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the first load started").isTrue();

        final CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(() -> this.engine.classify(request()));
        this.engine.shutdown();

        // nothing will ever settle the state now, so an untimed wait would park this caller for the
        // rest of the process — the caller is told instead
        assertThatThrownBy(() -> answer.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(RuntimeException.class)
                .hasMessageContaining("shut down before any rules were loaded");
        assertThat(failures()).as("shutdown is not a failure").isZero();
    }

    @Test
    void aSupersededReloadIsCancelledAndTheNewestWins() throws Exception {
        givenRulesLoaded("rules-1");

        final CountDownLatch supersededEntered = new CountDownLatch(1);
        final CountDownLatch neverReleased = new CountDownLatch(1);
        // Its own ruleset, because the test's name is a claim about which reload won and the served
        // value has to be able to say so. Publishing "rules-2" from both reloads would satisfy the
        // final assertion whether the newest won or the superseded one escaped cancellation, and
        // leave interrupted == 1 as the only thing separating those stories.
        this.delegate.nextRules = "rules-superseded";
        this.delegate.onReload = () -> {
            supersededEntered.countDown();
            neverReleased.await();
        };
        this.engine.reload();
        assertThat(supersededEntered.await(10, TimeUnit.SECONDS)).as("the superseded reload started").isTrue();

        // The winning reload parks here until this test lets it publish. That is what makes the two
        // assertions below observations rather than a race: they run while the publish is provably
        // still pending. Structural, not timed — delete the latch and those assertions start racing
        // the publish and failing, which is the property an earlier version of this test bought
        // with a sleep that nothing enforced.
        final CountDownLatch publish = new CountDownLatch(1);
        final CountDownLatch winnerEntered = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            winnerEntered.countDown();
            publish.await();
            // The publish lags the release, so a wait on a condition that is already true loses
            // the race every time instead of sometimes. This is the ONLY thing making a wrong wait
            // fail deterministically: measured, reverting the wait below with this line present
            // fails 5/5, and with it removed it survives 5/5 — the latch alone proves the
            // pre-publish state but does not catch a regression of the wait.
            Thread.sleep(PUBLISH_LAG_MILLIS);
        };
        this.delegate.nextRules = "rules-2";
        assertThat(successes()).as("only the construction-time load has succeeded so far").isEqualTo(1);

        this.engine.reload();
        assertThat(winnerEntered.await(10, TimeUnit.SECONDS)).as("the winning reload started").isTrue();

        // Why the counter and not the gauge, pinned instead of argued. With the publish still
        // pending, stale already reads 0 — it is set only by onReloadFailed and nothing here has
        // failed — so a wait on `stale() == 0` returns at exactly this point, while the rules below
        // are demonstrably not serving yet. That is #699, asserted rather than described.
        assertThat(stale()).as("stale is already 0 while the publish is still pending").isZero();
        assertThat(this.engine.classify(request()))
                .as("and the newest rules are not serving yet, so staleness cannot be the signal")
                .isEqualTo("rules-1");

        publish.countDown();

        // successes moves in onReloadSucceeded, strictly after delegate.reload() publishes, and the
        // superseded reload moves no counter at all — doReload treats an interrupt as neither a
        // success nor a failure. So this waits for the publish and for nothing else. Same idiom as
        // everySuccessfulReloadIsCounted. Written as >= so that an unexpected extra success fails
        // on the assertions below rather than spinning to the deadline on a target already passed.
        await("the winning reload to be counted", () -> successes() >= 2);

        assertThat(this.engine.classify(request()))
                .as("the newest reload's rules are the ones serving")
                .isEqualTo("rules-2");
        assertThat(successes()).as("and the superseded reload counted nothing").isEqualTo(2);
        assertThat(this.delegate.interrupted.get()).as("the in-flight reload was cancelled").isEqualTo(1);
        assertThat(failures()).as("cancellation is not a failure").isZero();
    }

    @Test
    void anInterruptedReloadIsNotCountedAsAFailure() throws Exception {
        givenRulesLoaded("rules-1");

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleased = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            entered.countDown();
            neverReleased.await();
        };
        this.engine.reload();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the reload started").isTrue();

        this.engine.shutdown();
        await("the reload thread to be interrupted", () -> this.delegate.interrupted.get() == 1);

        assertThat(failures()).as("shutdown is not a failure").isZero();
        assertThat(stale()).isEqualTo(0);
        assertThat(this.engine.classify(request())).isEqualTo("rules-1");
    }

    @Test
    void aReloadRequestedAfterShutdownIsNotAFailure() {
        givenRulesLoaded("rules-1");
        this.engine.shutdown();

        // the READY/FAILED arm: the executor refuses the task, and an orderly stop is not a rules failure
        assertThatCode(() -> this.engine.reload()).doesNotThrowAnyException();

        assertThat(failures()).as("shutdown is not a failure").isZero();
        assertThat(stale()).isEqualTo(0);
        assertThat(this.engine.classify(request())).isEqualTo("rules-1");
    }

    @Test
    void aReloadRequestedAfterShutdownMidReloadIsNotAFailure() throws Exception {
        givenRulesLoaded("rules-1");

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleased = new CountDownLatch(1);
        this.delegate.onReload = () -> {
            entered.countDown();
            neverReleased.await();
        };
        this.engine.reload();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the reload started").isTrue();
        this.engine.shutdown();

        // the RELOADING arm, which used to submit unguarded: the rejection escaped to the caller
        assertThatCode(() -> this.engine.reload()).doesNotThrowAnyException();

        assertThat(failures()).as("shutdown is not a failure").isZero();
        assertThat(stale()).isEqualTo(0);
        assertThat(this.engine.classify(request())).isEqualTo("rules-1");
    }

    /** Constructs the engine and blocks until its construction-time load has published {@code rules}. */
    private void givenRulesLoaded(final String rules) {
        this.delegate.nextRules = rules;
        this.engine = new AsyncReloadingClassificationEngine(this.delegate, this.metrics);
        // classify() itself blocks until the first load settles, so this is the wait
        assertThat(this.engine.classify(request())).isEqualTo(rules);
        assertThat(failures()).isZero();
    }

    private long failures() {
        return this.metrics.counter(FAILURE_COUNTER).getCount();
    }

    private long successes() {
        return this.metrics.counter(SUCCESS_COUNTER).getCount();
    }

    /**
     * The gauge's value as an {@code int}. {@code MetricRegistry.getGauges()} hands back a raw-valued map, so the
     * registered type cannot be checked by the compiler here; it is asserted instead, and callers then compare
     * {@code int} to {@code int} rather than an {@code Object} to a boxed one.
     */
    private int stale() {
        final Gauge<?> gauge = this.metrics.getGauges().get(STALE_GAUGE);
        assertThat(gauge).as("the stale gauge is registered").isNotNull();
        assertThat(gauge.getValue()).as("the stale gauge reads as an Integer").isInstanceOf(Integer.class);
        return (Integer) gauge.getValue();
    }

    private List<ILoggingEvent> eventsAt(final Level level) {
        return this.appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private static ClassificationRequest request() {
        return ClassificationRequest.builder().build();
    }

    /**
     * How long the winning reload delays its publish after the test releases it.
     *
     * <p>Deleting this downgrades the superseded-reload test silently: it keeps passing, and it
     * keeps proving the pre-publish state through its latch, but it stops catching a regression of
     * the wait it exists to protect. Measured both ways — see the comment at the call site.</p>
     */
    private static final long PUBLISH_LAG_MILLIS = 300;

    private static void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("timed out waiting for " + what);
    }

    /** A delegate whose reload can be made to succeed, throw, block, or be interrupted, per call. */
    private static final class ControllableEngine implements ClassificationEngine {

        @FunctionalInterface
        private interface ReloadAction {
            void run() throws InterruptedException;
        }

        private final AtomicInteger interrupted = new AtomicInteger();
        private volatile ReloadAction onReload = () -> { };
        private volatile String nextRules = "rules-1";
        private volatile String serving = "<nothing ever loaded>";

        @Override
        public void reload() throws InterruptedException {
            try {
                this.onReload.run();
            } catch (final InterruptedException e) {
                this.interrupted.incrementAndGet();
                throw e;
            }
            // the property the fix rests on: the delegate publishes atomically, so what was
            // serving stays serving until a reload gets all the way here. That this fake HAS the
            // property is not evidence that the real delegate does — DefaultClassificationEngineTest
            // pins that separately, against the engine actually wrapped in production
            this.serving = this.nextRules;
        }

        @Override
        public String classify(final ClassificationRequest classificationRequest) {
            return this.serving;
        }

        @Override
        public List<Rule> getInvalidRules() {
            // derived from the same single field read that classify() answers from, so an invalid-rule
            // answer can never belong to a different ruleset than the classification does
            return List.of(DefaultRule.builder().withName(this.serving + "-invalid").build());
        }

        @Override
        public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener listener) {
            // no listener behaviour under test
        }

        @Override
        public void removeClassificationRulesReloadedListener(final ClassificationRulesReloadedListener listener) {
            // no listener behaviour under test
        }
    }
}
