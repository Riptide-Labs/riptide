/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The startup-wait loop without a server (#833): a scripted probe and a clock that advances only
 * when the loop sleeps, so every assertion is about the policy and none about wall time. What the
 * probe classifies as an answer is {@code ClickhouseStartupWaitIT}'s to prove against a real
 * ClickHouse.
 */
class StartupWaitTest {

    private static final String ENDPOINT = "http://clickhouse:8123";

    /** Advances only through {@link #sleep}, so a test controls exactly when the window elapses. */
    private long nowNanos;
    private final List<Duration> sleeps = new ArrayList<>();

    private Logger log;
    private ListAppender<ILoggingEvent> events;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        this.log = (Logger) LoggerFactory.getLogger(StartupWait.class);
        this.originalLevel = this.log.getLevel();
        this.log.setLevel(Level.INFO);
        this.events = LogCapture.startedAppender();
        this.log.addAppender(this.events);
    }

    @AfterEach
    void releaseLog() {
        this.log.detachAppender(this.events);
        this.log.setLevel(this.originalLevel);
    }

    private StartupWait wait(final Duration window) {
        return new StartupWait(window, () -> this.nowNanos, this::sleep);
    }

    private void sleep(final Duration duration) {
        this.sleeps.add(duration);
        this.nowNanos += duration.toNanos();
    }

    private static StartupWait.Outcome silent() {
        return new StartupWait.Outcome.Silent(new ConnectException("Connection refused"));
    }

    private static StartupWait.Outcome answered() {
        return new StartupWait.Outcome.Answered();
    }

    /** A probe that yields the scripted outcomes in order and counts how often it was asked. */
    private static final class Script implements StartupWait.Probe {
        private final Deque<StartupWait.Outcome> outcomes;
        int attempts;

        Script(final StartupWait.Outcome... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public StartupWait.Outcome probe() {
            this.attempts++;
            if (this.outcomes.isEmpty()) {
                throw new AssertionError("probed more often than scripted: " + this.attempts);
            }
            return this.outcomes.pop();
        }
    }

    @Test
    void aLateBackendInsideTheWindowIsTolerated() {
        final var script = new Script(silent(), silent(), answered());

        wait(Duration.ofSeconds(30)).await(ENDPOINT, script);

        Assertions.assertThat(script.attempts).isEqualTo(3);
        Assertions.assertThat(this.sleeps).containsExactly(StartupWait.INTERVAL, StartupWait.INTERVAL);
        // One WARN per silent attempt, naming the endpoint, the attempt and the window; then one
        // INFO saying after how many attempts the server answered.
        Assertions.assertThat(this.events.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(2)
                .allSatisfy(message -> Assertions.assertThat(message)
                        .contains(ENDPOINT).contains("of PT30S").contains("Connection refused"))
                .satisfies(messages -> {
                    Assertions.assertThat(messages.get(0)).contains("attempt 1,");
                    Assertions.assertThat(messages.get(1)).contains("attempt 2,");
                });
        Assertions.assertThat(this.events.list).filteredOn(e -> e.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString()
                .contains(ENDPOINT).contains("after 3 attempts");
    }

    @Test
    void aBackendStillSilentAfterTheWindowFailsStartup() {
        // A 5 s window with a 2 s interval probes at 0, 2, 4 and 5 s: the last pause is the
        // remainder, not a full interval, so the final attempt lands at the window's end.
        final var script = new Script(silent(), silent(), silent(), silent());

        Assertions.assertThatThrownBy(() -> wait(Duration.ofSeconds(5)).await(ENDPOINT, script))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENDPOINT)
                .hasMessageContaining("within PT5S")
                .hasMessageContaining("4 attempt(s)")
                .hasMessageContaining("Connection refused")
                .hasMessageContaining(StartupWait.KEY)
                .hasCauseInstanceOf(ConnectException.class);

        Assertions.assertThat(script.attempts).isEqualTo(4);
        Assertions.assertThat(this.sleeps).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1));
        Assertions.assertThat(this.events.list).filteredOn(e -> e.getLevel() == Level.INFO).isEmpty();
    }

    @Test
    void aZeroWindowProbesExactlyOnce() {
        final var script = new Script(silent());

        Assertions.assertThatThrownBy(() -> wait(Duration.ZERO).await(ENDPOINT, script))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 attempt(s)")
                .hasMessageContaining(StartupWait.KEY);

        Assertions.assertThat(script.attempts).isEqualTo(1);
        Assertions.assertThat(this.sleeps).isEmpty();
    }

    @Test
    void anAnswerOnTheFirstProbeIsSilentInTheLog() {
        final var script = new Script(answered());

        wait(Duration.ofSeconds(30)).await(ENDPOINT, script);

        Assertions.assertThat(script.attempts).isEqualTo(1);
        Assertions.assertThat(this.sleeps).isEmpty();
        Assertions.assertThat(this.events.list).isEmpty();
    }

    @Test
    void aNegativeWindowIsRejectedNamingTheKey() {
        Assertions.assertThatThrownBy(() -> wait(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StartupWait.KEY)
                .hasMessageContaining("PT-5S");
        Assertions.assertThatThrownBy(() -> wait(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StartupWait.KEY);
    }

    @Test
    void anInterruptedWaitSaysSoAndRestoresTheFlag() {
        final StartupWait interruptedSleep = new StartupWait(Duration.ofSeconds(30), () -> this.nowNanos,
                duration -> {
                    throw new InterruptedException("torn down");
                });
        final var script = new Script(silent());

        try {
            Assertions.assertThatThrownBy(() -> interruptedSleep.await(ENDPOINT, script))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted while waiting for ClickHouse at " + ENDPOINT)
                    .hasMessageNotContaining("did not answer")
                    .hasCauseInstanceOf(InterruptedException.class);
            Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear it, or the next test on this worker inherits a pending interrupt.
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptedProbeSaysSoToo() {
        final StartupWait wait = wait(Duration.ofSeconds(30));

        try {
            Assertions.assertThatThrownBy(() -> wait.await(ENDPOINT, () -> {
                        throw new InterruptedException("torn down");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted while waiting for ClickHouse")
                    .hasMessageNotContaining("did not answer");
            Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
