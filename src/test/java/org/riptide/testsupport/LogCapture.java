/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place the test tier builds a log-capturing appender, so that no test author has to know
 * the hazard below exists.
 *
 * <p>Logback's {@link ListAppender} captures into a plain {@code ArrayList}. Appends themselves are
 * safe: {@code AppenderBase.doAppend} is {@code synchronized} (verified with {@code javap} against
 * logback-core 1.5.38), so two producers cannot corrupt the list and no event is ever lost. The
 * hazard is entirely on the <em>reader</em>. Every test here that asserts on log output has a
 * background thread producing it — a reload thread, a flusher, a poller — and streaming that list
 * while such a thread appends throws {@code ConcurrentModificationException} out of
 * {@code ArrayList}'s spliterator. It failed that way in
 * {@code AsyncReloadingClassificationEngineTest} (#735), and it reads like a concurrency defect in
 * the subject under test rather than in the capture helper.</p>
 *
 * <p>Because appends hold the appender's monitor, a reader <em>can</em> be made correct without
 * touching the container: {@code synchronized (appender) { List.copyOf(appender.list); }} is safe.
 * That was rejected on ergonomics, not correctness. It puts lock discipline on every one of the 24
 * call sites and on every future one, and the failure mode for forgetting is an intermittent
 * exception in an unrelated test. Replacing the container moves the rule to one place instead.
 * (An <em>unsynchronised</em> copy — {@code new ArrayList<>(appender.list)} — is not a fix: the copy
 * constructor iterates the same list and races identically. That is the version worth warning
 * about, because it looks like a fix.)</p>
 *
 */
public final class LogCapture {

    private LogCapture() {
    }

    /**
     * A started {@link ListAppender}, ready to hand to {@code logger.addAppender(...)}, whose
     * captured events are safe to read while another thread is still logging.
     *
     * <p>{@link CopyOnWriteArrayList} because its iterators are snapshots, which is exactly the
     * read model these tests want: a poll loop asks "what has been logged so far", and a snapshot
     * answers that without tearing. The one thing it forbids is removal through an iterator — no
     * caller does that, and a caller that needs to would need a different container rather than a
     * silent behaviour change.</p>
     *
     * <p>Its append is O(n), so a capture is not the place for a subject that logs without bound.
     * The largest in this suite is {@code LogCaptureTest}'s own 5,000 events, which is milliseconds;
     * a test that wants hundreds of thousands should assert on a counter instead of a transcript.</p>
     *
     * <p>What this does <em>not</em> promise is a complete capture. A read taken while a producer is
     * still running sees what had been appended by then, which is the right answer for a poll loop
     * and the wrong one for an exact-count or negative assertion — those still have to quiesce the
     * producer first. It converts a loud {@code ConcurrentModificationException} into a quiet
     * under-count, and the caller chooses which it wants.</p>
     */
    public static ListAppender<ILoggingEvent> startedAppender() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        // The one line that matters. ListAppender.list is public and non-final in logback-core
        // 1.5.38, which is what makes this possible at all; a version that finalises it breaks here
        // loudly at compile time.
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        return appender;
    }
}
