/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.testsupport.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP request deadline (#545).
 *
 * <p><b>What is deliberately not tested here, and why.</b> The property must be set before
 * the first {@code HttpServer} in the process, because {@code sun.net.httpserver.ServerConfig}
 * reads it in a static initializer. That ordering cannot be asserted in-process: by the time
 * any test runs, some earlier test has usually created a server, so the value this class sets
 * has no further effect in that JVM. An earlier version of this suite asserted only that the
 * property ends up set, which passes whether {@code ensureApplied()} runs before or after
 * {@code create()} — it guarded nothing. Proving the ordering would need a forked JVM with a
 * short deadline and a half-open socket; the ordering itself is instead enforced structurally,
 * by each server calling {@code ensureApplied()} on the line before it creates its server.</p>
 */
class HttpServerConfigTest {

    private String original;

    @AfterEach
    void restore() {
        if (this.original == null) {
            System.clearProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY);
        } else {
            System.setProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY, this.original);
        }
    }

    private void capture() {
        this.original = System.getProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY);
    }

    @Test
    void appliesTheDeadlineWhenTheOperatorSetNone() {
        capture();
        System.clearProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY);

        HttpServerConfig.ensureApplied();

        assertThat(System.getProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY))
                .isEqualTo(HttpServerConfig.MAX_REQUEST_SECONDS);
    }

    /** An operator value wins: this is a safety rail with an escape hatch, not a knob. */
    @Test
    void leavesAnOperatorSuppliedValueAlone() {
        capture();
        System.setProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY, "90");

        HttpServerConfig.ensureApplied();

        assertThat(System.getProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY)).isEqualTo("90");
    }

    /**
     * The escape hatch must not be silent. The JDK swallows a malformed or empty value and
     * falls back to no deadline at all, so a truncated JAVA_OPTS line would otherwise switch
     * the protection off with nothing in the log.
     */
    @Test
    void warnsWhenTheSuppliedValueDisablesTheDeadline() {
        capture();
        for (final String disabling : new String[] {"-1", "0", ""}) {
            System.setProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY, disabling);
            final var appender = capture(HttpServerConfig.class);
            try {
                HttpServerConfig.ensureApplied();
                assertThat(appender.list)
                        .as("'%s' leaves requests unbounded and must say so", disabling)
                        .anySatisfy(event -> assertThat(event.getFormattedMessage())
                                .contains("without a deadline"));
            } finally {
                release(HttpServerConfig.class, appender);
            }
            // the operator's value is still respected, warning or not
            assertThat(System.getProperty(HttpServerConfig.MAX_REQUEST_TIME_PROPERTY)).isEqualTo(disabling);
        }
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture(
            final Class<?> loggerClass) {
        final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        return appender;
    }

    private static void release(final Class<?> loggerClass,
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass)).detachAppender(appender);
    }
}
