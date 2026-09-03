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
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Points logging at one explicit stream, for the subcommands that run without a Spring context.
 *
 * <p>Those subcommands never load {@code logback-spring.xml} — Spring Boot loads it, Logback does
 * not — so Logback falls back to a console appender on <em>stdout</em>. That is the stream
 * {@code riptide convert} writes the generated configuration to, so a single log record reached
 * during a conversion lands inside the file the operator redirected it into.</p>
 *
 * <p>Reconfiguring here rather than redirecting {@code System.out} is the same constraint
 * {@code logback-spring.xml} documents for the MCP stdio transport: {@code ConsoleAppender}
 * resolves and caches its stream when the appender starts, so a later {@code System.setOut} never
 * reaches it. Redirecting stdout would also silence the generated document, which is the one thing
 * that belongs there.</p>
 *
 * <p>The destination is a parameter rather than a {@code ConsoleAppender} target, so production
 * passes {@code System.err} and a test passes a buffer it can read: the assertion is then about
 * where a record went rather than about global state.</p>
 */
public final class CliLogging {

    /** Matches the console pattern a Spring-started run prints, so a CLI line reads the same. */
    private static final String PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -- %msg%n";

    private CliLogging() {
    }

    /**
     * Routes every log record to {@code stream}, replacing whatever the root logger writes to.
     *
     * <p>A no-op when the SLF4J binding is not Logback: a CLI must not fail because logging could
     * not be reconfigured.</p>
     */
    public static void routeTo(final OutputStream stream) {
        routeTo(LoggerFactory.getILoggerFactory(), stream);
    }

    /** As {@link #routeTo(OutputStream)}, but against a caller-supplied binding. */
    static void routeTo(final ILoggerFactory factory, final OutputStream stream) {
        if (!(factory instanceof LoggerContext context)) {
            return;
        }
        final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        final OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("CLI");
        appender.setEncoder(encoder);
        appender.setOutputStream(stream);
        appender.start();

        final ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        // detached, not stopped: what is being replaced is Logback's fallback appender on stdout,
        // which owns no resource to release, and stopping an appender is not undone by attaching
        // it again — which is what a test restoring the context has to do
        final List<Appender<ILoggingEvent>> replaced = new ArrayList<>();
        root.iteratorForAppenders().forEachRemaining(replaced::add);
        replaced.forEach(root::detachAppender);
        root.addAppender(appender);
        // the fallback configuration roots at DEBUG; logback-spring.xml roots at INFO, and a
        // subcommand printing every library's debug line would bury its own summary
        root.setLevel(Level.INFO);
    }
}
