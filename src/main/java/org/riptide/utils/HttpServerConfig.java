/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.utils;

import lombok.extern.slf4j.Slf4j;

/**
 * JVM-wide configuration for the JDK's {@code com.sun.net.httpserver}, applied before the
 * first server is created (#545).
 *
 * <p><b>Why this is not simply set at each server's start.</b>
 * {@code sun.net.httpserver.ServerConfig} reads its properties in a static initializer that
 * runs when the <em>first</em> {@link com.sun.net.httpserver.HttpServer} in the process is
 * created — probe-verified: setting the property after that point has no effect on a
 * subsequently created server. This project has two servers (the management server and
 * the opt-in MCP SSE transport), so a fix living in one of them would be silently
 * order-dependent. Rather than declaring a bean order and hoping it holds, both servers
 * call {@link #ensureApplied()} immediately before creating their server: whichever runs
 * first applies the configuration, and the ordering question disappears.</p>
 *
 * <p><b>Only the request deadline is set, deliberately.</b> {@code maxRspTime} would bound
 * how long a <em>response</em> may take, and the MCP SSE transport parks its GET handler on
 * a session for as long as the stream lives — setting it would sever every MCP stream at
 * the deadline. It is also the less necessary of the two here: a client that reads a
 * response slowly is executing inside a handler, so it holds one of the management server's
 * admission permits and is already bounded by {@code max-concurrent-requests}. A client that
 * never finishes sending its <em>request</em> never reaches a handler, takes no permit, and
 * was bounded by nothing at all — which is what {@code maxReqTime} closes.</p>
 */
@Slf4j
public final class HttpServerConfig {

    /**
     * Seconds allowed to receive a complete request. Generous for the traffic these servers
     * see — a probe or a metrics scrape sends its request in one segment — while bounding a
     * client that opens a connection and dribbles a request forever.
     */
    public static final String MAX_REQUEST_SECONDS = "30";

    public static final String MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";

    private HttpServerConfig() {
    }

    /**
     * Applies the configuration if it has not been applied already. Idempotent and safe to
     * call from every server's startup path; callers must call it <em>before</em> creating
     * an {@link com.sun.net.httpserver.HttpServer}.
     *
     * <p>An operator-supplied value wins: this only fills in a property that is absent, so
     * {@code -Dsun.net.httpserver.maxReqTime=…} on the command line still decides. That is
     * also why this is not a {@code riptide.management.*} setting — it is a safety rail with
     * an escape hatch, not a tuning knob worth advertising.</p>
     */
    public static void ensureApplied() {
        final String supplied = System.getProperty(MAX_REQUEST_TIME_PROPERTY);
        if (supplied == null) {
            System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_SECONDS);
            return;
        }
        // an operator value wins, but it does not get to disable the rail in silence: the
        // JDK swallows a malformed or empty value and falls back to -1 (no deadline), so a
        // truncated JAVA_OPTS line would otherwise turn the protection off with no signal
        if (Long.getLong(MAX_REQUEST_TIME_PROPERTY, -1L) <= 0L) {
            log.warn("{}={} leaves HTTP requests without a deadline: a client that opens a connection "
                    + "and never finishes sending is bounded by nothing. Set a positive number of seconds "
                    + "(riptide's default is {}) unless this is deliberate.",
                    MAX_REQUEST_TIME_PROPERTY, supplied, MAX_REQUEST_SECONDS);
        }
    }
}
