/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Where a log record goes once a context-free subcommand has routed logging.
 *
 * <p>The destination is asserted, not assumed. {@code riptide convert nodes.yaml} redirected into
 * a file puts the generated configuration on stdout, and before this routing existed Logback's
 * fallback appender put log records on the same stream — inside the operator's new file.</p>
 */
class CliLoggingTest {

    private ch.qos.logback.classic.Logger root;
    private List<Appender<ILoggingEvent>> attached;
    private Level level;

    @BeforeEach
    void captureLoggingSetup() {
        this.root = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.attached = new ArrayList<>();
        this.root.iteratorForAppenders().forEachRemaining(this.attached::add);
        this.level = this.root.getLevel();
    }

    @AfterEach
    void restoreLoggingSetup() {
        // detached, not stopped: on the no-op test these are the very appenders the rest of the
        // suite logs through, and stopping one is not undone by attaching it again
        final List<Appender<ILoggingEvent>> current = new ArrayList<>();
        this.root.iteratorForAppenders().forEachRemaining(current::add);
        current.forEach(this.root::detachAppender);
        this.attached.forEach(this.root::addAppender);
        this.root.setLevel(this.level);
    }

    /**
     * The first row of the matrix, without a subprocess: a record emitted after the routing is
     * installed reaches the supplied stream, and the appender it replaced gets nothing.
     *
     * <p>The stand-in is what makes the second half assertable. {@code System.setOut} cannot be
     * used to catch a leak here: Logback's fallback appender cached the real stdout when it
     * started, long before this test ran, so a reassignment would prove only that the appender was
     * already holding the other stream. An appender attached and started here holds a buffer this
     * test owns, and it stands in for the fallback for exactly the reason the fallback is a
     * problem — the routing has to <em>replace</em> it, not merely add a second destination.</p>
     */
    @Test
    void aRecordReachesTheSuppliedStreamAndNotTheAppenderItReplaced() {
        final ByteArrayOutputStream routed = new ByteArrayOutputStream();
        final ByteArrayOutputStream standInForStdout = new ByteArrayOutputStream();
        this.root.addAppender(appenderOn(standInForStdout));

        CliLogging.routeTo(routed);
        LoggerFactory.getLogger(CliLoggingTest.class).warn("a warning reached during conversion");

        assertThat(routed.toString(StandardCharsets.UTF_8))
                .as("the caller's stream is where the record went")
                .contains("WARN")
                .contains("a warning reached during conversion");
        assertThat(standInForStdout.toString(StandardCharsets.UTF_8))
                .as("stdout carries the generated configuration and nothing else")
                .isEmpty();
    }

    /** An appender on {@code stream}, standing in for the one Logback's fallback puts on stdout. */
    private OutputStreamAppender<ILoggingEvent> appenderOn(final ByteArrayOutputStream stream) {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%-5level %msg%n");
        encoder.start();
        final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("stand-in-for-stdout");
        appender.setEncoder(encoder);
        appender.setOutputStream(stream);
        appender.start();
        return appender;
    }

    /** INFO is the level a Spring-started run logs at; the fallback configuration roots at DEBUG. */
    @Test
    void debugRecordsAreNotRouted() {
        final ByteArrayOutputStream routed = new ByteArrayOutputStream();

        CliLogging.routeTo(routed);
        LoggerFactory.getLogger(CliLoggingTest.class).debug("a library's internals");

        assertThat(routed.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /**
     * The other half of the level, and the half that was missing: an INFO record <em>is</em>
     * routed.
     *
     * <p>{@link #debugRecordsAreNotRouted} only pins that the level sits above DEBUG, so it is
     * satisfied by WARN or ERROR just as well as by INFO — a mutation to {@code Level.WARN}
     * survived the suite until this test existed. INFO is the level the comment on
     * {@code root.setLevel} argues for, so it is the level that has to be asserted.</p>
     */
    @Test
    void infoRecordsAreRouted() {
        final ByteArrayOutputStream routed = new ByteArrayOutputStream();

        CliLogging.routeTo(routed);
        LoggerFactory.getLogger(CliLoggingTest.class).info("something an operator should read");

        assertThat(routed.toString(StandardCharsets.UTF_8))
                .as("INFO is the level a Spring-started run logs at, so a CLI must not sit above it")
                .contains("something an operator should read");
    }

    /**
     * The fourth row: a CLI must not fail because logging could not be reconfigured, so a binding
     * that is not Logback is left alone rather than cast.
     */
    @Test
    void aNonLogbackBindingIsLeftAlone() {
        final ByteArrayOutputStream routed = new ByteArrayOutputStream();

        assertThatCode(() -> CliLogging.routeTo(name -> NOPLogger.NOP_LOGGER, routed))
                .doesNotThrowAnyException();
        assertThat(routed.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
