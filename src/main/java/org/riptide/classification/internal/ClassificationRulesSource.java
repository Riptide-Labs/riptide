/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import org.riptide.config.ClassificationConfig;
import org.riptide.config.FileWatchTrigger;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one read of {@code riptide.classification.rules}, shared by the provider that
 * parses the rules and by the reload trigger that watches them.
 *
 * <p><b>Why it is not just {@code Resource.getInputStream()}</b>: a {@code Resource} for
 * a remote location opens a {@code URLConnection} with no timeouts and no ceiling, so a
 * server that accepts the connection and never answers parks the reading thread for as
 * long as the socket lives, and one that answers with a gigabyte parks it until the heap
 * runs out. That thread is either the engine's single reload thread or the reloader's
 * schedule, and both of them stop reloading anything for the rest of the process — an
 * {@code OutOfMemoryError} out of the schedule cancels it permanently. Both readers go
 * through here, so both are bounded; timing out one and leaving the other unbounded would
 * only move the hang.
 *
 * <p><b>What bounded means</b>: a connect timeout, a per-read timeout, <em>and</em> a
 * deadline across the whole response — the first two alone are not a bound, because a
 * server dribbling one byte per read resets the read timer forever. The deadline is
 * checked before every read, so the worst case is the deadline plus one read timeout, and
 * a response is refused outright past {@link #MAX_BYTES}. A non-200 status is a failure
 * naming the code, so a redirect this class does not follow or a proxy's error page never
 * reaches the CSV parser; 404 alone is absence, which the trigger skips.
 *
 * <p><b>Resolution is unchanged</b> for a local resource: a {@code classpath:},
 * {@code file:} or {@code jar:} resource is read through {@code Resource.getInputStream()},
 * exactly as before. Everything else is a network read and is bounded, whatever its
 * scheme. The resource is re-read on every call so an edit is seen.
 */
public final class ClassificationRulesSource implements FileWatchTrigger.Source {

    /**
     * Bounds a remote fetch: the connect, each read, and the whole response. Generous for
     * a rules CSV over a healthy link. A cycle against a hung server therefore ends within
     * roughly twice this, because a read already blocked when the deadline passes still
     * has to time out on its own; that is also the longest an orderly shutdown waits for a
     * poll parked on one.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Refusal ceiling for a fetched ruleset. The largest ruleset anyone would hand this
     * engine is a few hundred kilobytes, and the alternative to a ceiling is
     * {@code readAllBytes} against a source whose disk nobody here controls — where the
     * realistic outcome is an {@code OutOfMemoryError} on the poll thread, which cancels
     * the schedule for the rest of the process rather than failing one cycle.
     */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    /** How much of an error response is drained so the connection can be pooled again. */
    private static final int ERROR_DRAIN_LIMIT = 64 * 1024;

    /**
     * Credentials embedded in a location, as {@code scheme://user:token@host}. The docs
     * say the endpoint carries no authentication, which makes reaching for this shape the
     * natural next move — and the location is logged at INFO on startup and in every
     * failure WARN.
     */
    private static final Pattern USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s\\]]*@");

    private final ClassificationConfig config;
    private final Duration timeout;

    public ClassificationRulesSource(final ClassificationConfig config) {
        this(config, DEFAULT_TIMEOUT);
    }

    /** Visible for tests, which cannot wait out the default timeout on every hung-server row. */
    ClassificationRulesSource(final ClassificationConfig config, final Duration timeout) {
        this.config = Objects.requireNonNull(config);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * The rules bytes as the resource has them now.
     *
     * @throws FileNotFoundException when the resource is not there — a 404, a deleted
     *     file, a classpath entry that does not resolve. Absence is a skip, not a failure,
     *     and the caller has to be able to tell it from an unreachable server. Note that a
     *     local file that is <em>there and unreadable</em> also arrives this way (a
     *     permission denial on a {@code file:} URL is a {@code FileNotFoundException}
     *     naming "Permission denied"); {@link #fetch()} separates the two.
     * @throws IOException for everything else: refused connections, DNS failures, a
     *     non-200 status, a read that timed out, a response past {@link #MAX_BYTES}.
     */
    public byte[] read() throws IOException {
        return read(this.config.getRules());
    }

    private byte[] read(final Resource resource) throws IOException {
        final URL remote = remoteUrl(resource);
        if (remote == null) {
            try (InputStream in = resource.getInputStream()) {
                return readBounded(in, null);
            }
        }
        return fetchRemote(remote);
    }

    @Override
    public FileWatchTrigger.Fetch fetch() throws IOException {
        final Resource resource = this.config.getRules();
        try {
            return new FileWatchTrigger.Fetch.Present(read(resource));
        } catch (final FileNotFoundException e) {
            if (remoteUrl(resource) == null && resource.exists()) {
                // there, and unreadable: a permission denial, or a path that is not a
                // regular file. Skipping this forever would count nothing and warn about
                // a file that is present — the operator would be told to make it reappear
                throw e;
            }
            // a 404 or a deleted file: the last good rules keep classifying and the
            // trigger warns once. Never Vanished — no remote source can tell an atomic
            // replacement from a deletion, and pretending otherwise would silence a 404.
            // exists() is not consulted for a remote resource: Spring would answer it with
            // a second round trip, and the status check below has already decided
            return new FileWatchTrigger.Fetch.Absent();
        }
    }

    /** The location, with any embedded credentials removed; safe to log. */
    @Override
    public String describe() {
        return USERINFO.matcher(this.config.getRules().getDescription()).replaceAll("$1***@");
    }

    /**
     * The resource's URL when reading it is a network read, else {@code null}. File and
     * jar URLs stay on Spring's own path so their resolution is exactly what it was.
     */
    private static URL remoteUrl(final Resource resource) {
        final URL url;
        try {
            url = resource.getURL();
        } catch (final IOException e) {
            // a resource with no URL at all (a byte array in a test) or a classpath entry
            // that does not resolve: read it the way it has always been read, and let that
            // read report its own absence
            return null;
        }
        return ResourceUtils.isFileURL(url) || ResourceUtils.isJarURL(url) ? null : url;
    }

    /**
     * Opens the connection this class is willing to read from. Split out so the timeouts
     * the production constructor applies are observable without a ten-second test: nothing
     * else reaches them, and a {@code Duration.ZERO} default would read as "no timeout" to
     * {@code URLConnection} while every behavioural test kept passing on its own injected
     * timeout.
     */
    URLConnection openBounded(final URL url) throws IOException {
        final URLConnection connection = url.openConnection();
        connection.setConnectTimeout(timeoutMillis());
        connection.setReadTimeout(timeoutMillis());
        // the content hash decides whether anything is rebuilt, so a cached response would
        // only hide a change from it
        connection.setUseCaches(false);
        return connection;
    }

    private byte[] fetchRemote(final URL url) throws IOException {
        final URLConnection connection = openBounded(url);
        final HttpURLConnection http = connection instanceof HttpURLConnection h ? h : null;
        final long deadline = System.nanoTime() + this.timeout.toNanos();
        try {
            if (http != null) {
                final int status = http.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    // the one status that is absence rather than failure
                    throw new FileNotFoundException("%s answered 404".formatted(describe()));
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    // a 3xx this connection did not follow, a 5xx, or a proxy's error page:
                    // handing that body to the CSV parser would report a rules problem for
                    // what is a transport problem
                    throw new IOException("%s answered HTTP %d, not 200".formatted(describe(), status));
                }
            }
            try (InputStream in = connection.getInputStream()) {
                return readBounded(in, deadline);
            }
        } catch (final IOException e) {
            // drain first: an undrained error body keeps the socket out of the keep-alive
            // pool, and this endpoint is polled again on every interval. A socket we
            // abandoned mid-response cannot be reused, so that one is closed instead
            release(http, !(e instanceof SocketTimeoutException));
            throw e;
        }
    }

    /**
     * Reads to the end, refusing to grow past {@link #MAX_BYTES} and to keep reading past
     * {@code deadline} — a {@code System.nanoTime()} reading, or {@code null} for a local
     * read, which has no peer to stall on. The deadline is what makes the bound a bound: a
     * per-read timeout is reset by every byte that arrives, so a server sending one byte
     * just inside it holds the thread forever. Boxed rather than sentinelled because
     * {@code nanoTime()} may legitimately be negative, so no {@code long} value is free to
     * mean "none".
     */
    private byte[] readBounded(final InputStream in, final Long deadline) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        while (true) {
            if (deadline != null && System.nanoTime() - deadline > 0) {
                throw new SocketTimeoutException(
                        "%s did not finish responding within %s".formatted(describe(), this.timeout));
            }
            final int read = in.read(buffer);
            if (read < 0) {
                return out.toByteArray();
            }
            if (out.size() + read > MAX_BYTES) {
                throw new IOException("%s is larger than the %d byte ceiling for a ruleset"
                        .formatted(describe(), MAX_BYTES));
            }
            out.write(buffer, 0, read);
        }
    }

    private static void release(final HttpURLConnection http, final boolean reusable) {
        if (http == null) {
            return;
        }
        boolean drained = true;
        try (InputStream errors = http.getErrorStream()) {
            if (errors != null) {
                errors.readNBytes(ERROR_DRAIN_LIMIT);
            }
        } catch (final IOException e) {
            drained = false;
        }
        if (!reusable || !drained) {
            http.disconnect();
        }
    }

    /**
     * The timeout as {@code URLConnection} wants it. Clamped rather than thrown: an
     * operator-sized {@code Duration} beyond 24 days is absurd, but turning it into an
     * {@code ArithmeticException} on the poll thread would be worse than treating it as
     * the longest timeout the API can express.
     */
    private int timeoutMillis() {
        return (int) Math.min(this.timeout.toMillis(), Integer.MAX_VALUE);
    }
}
