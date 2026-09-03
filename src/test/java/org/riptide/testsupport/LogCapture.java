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
 * <p>Logback's {@link ListAppender} captures into a plain {@code ArrayList} and its {@code append}
 * does a bare {@code list.add(e)} with no synchronisation. Every test here that asserts on log
 * output has a background thread producing it — a reload thread, a flusher, a poller — and reading
 * that list while such a thread appends is a data race: {@code ConcurrentModificationException} out
 * of the reader, or a silently lost event. It failed that way in
 * {@code AsyncReloadingClassificationEngineTest} (#735), and it reads like a concurrency defect in
 * the subject under test rather than in the capture helper.</p>
 *
 * <p>Neither of the obvious repairs works. Synchronising the reader buys nothing, because
 * {@code append} does not take the same lock; copying with {@code new ArrayList<>(appender.list)}
 * iterates the very same unsynchronised list and races identically. Replacing the container is the
 * only fix that holds without patching Logback, and {@code ListAppender.list} is a public non-final
 * field, so it can simply be replaced.</p>
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
     * answers that without tearing. Write cost is irrelevant at test volumes. The one thing it
     * forbids is removal through an iterator — no caller does that, and a caller that needs to
     * would need a different container rather than a silent behaviour change.</p>
     */
    public static ListAppender<ILoggingEvent> startedAppender() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        return appender;
    }
}
