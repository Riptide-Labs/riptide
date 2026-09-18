/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The one bounded read every remote configuration source in this project shares: a connect
 * timeout, a per-read timeout, a whole-response deadline and a size ceiling.
 *
 * <p><b>Why it exists</b>: {@code Resource.getInputStream()} on a remote location has neither
 * a timeout nor a ceiling, so a hung server parks a poll thread forever and an oversized answer
 * is read into the heap. {@link ClassificationRulesSource} grew all four bounds; discovery needs
 * the same four. A second copy is the shape {@link FileWatchTrigger}'s own history warns about,
 * where two copies of one rule drifted until a whitespace-only file was a benign skip for one
 * reloader and a counted failure for the other (#561).</p>
 *
 * <p><b>404 is absence, not failure.</b> It arrives as {@link FileNotFoundException} so a caller
 * can map it to {@link FileWatchTrigger.Fetch.Absent} and keep serving. Every other non-200 is an
 * {@link IOException} naming the status, because a 500 is a server in trouble, not a deletion.</p>
 *
 * <p>No byte-order-mark handling here. Callers strip one with {@link ByteOrderMark} after the read,
 * because the local and remote branches converge there rather than here.</p>
 */
public final class BoundedHttpRead {

    /** How much of an error response is drained so the connection can be pooled again. */
    private static final int ERROR_DRAIN_LIMIT = 64 * 1024;

    /**
     * Credentials embedded in a location, as {@code scheme://user:token@host}. The docs say
     * neither endpoint carries authentication in the URL, which makes reaching for this shape the
     * natural next move — and a location is logged at INFO on startup and in every failure WARN.
     */
    private static final Pattern USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s\\]]*@");

    private final Duration timeout;
    private final int maxBytes;
    private final String subject;
    private final Supplier<String> describe;
    private final Supplier<Map<String, String>> headers;
    private final OutboundHttpTrust trust;

    /**
     * @param timeout bounds the connect, each read, and the whole response. A cycle against a hung
     *     server therefore ends within roughly twice this, because a read already blocked when the
     *     deadline passes still has to time out on its own.
     * @param maxBytes refusal ceiling for the response body
     * @param subject the noun in the ceiling message, e.g. {@code "ruleset"}
     * @param describe the location with credentials redacted; safe to log, evaluated per message
     * @param headers request headers to set, e.g. an {@code Authorization} header. A supplier
     *     rather than a value, and asked once per opened connection — per request, which for a
     *     caller that pages is once per page rather than once per poll. A caller whose header
     *     carries a credential can then resolve its reference per read, so a rotation takes effect
     *     on the next request instead of at the next restart (#804). A caller with nothing to send
     *     supplies an empty map, which is what it passed as a value before.
     */
    public BoundedHttpRead(final Duration timeout,
                           final int maxBytes,
                           final String subject,
                           final Supplier<String> describe,
                           final Supplier<Map<String, String>> headers) {
        this(timeout, maxBytes, subject, describe, headers, new OutboundHttpTrust());
    }

    /**
     * @param trust which certificate authorities this reader accepts. Here rather than at either
     *     call site because this is the one place a connection is opened, so a bundle configured
     *     once reaches every consumer without any of them knowing about it (#802).
     */
    public BoundedHttpRead(final Duration timeout,
                           final int maxBytes,
                           final String subject,
                           final Supplier<String> describe,
                           final Supplier<Map<String, String>> headers,
                           final OutboundHttpTrust trust) {
        this.timeout = Objects.requireNonNull(timeout);
        this.maxBytes = maxBytes;
        this.subject = Objects.requireNonNull(subject);
        this.describe = Objects.requireNonNull(describe);
        this.headers = Objects.requireNonNull(headers);
        this.trust = Objects.requireNonNull(trust);
    }

    /**
     * Reads a remote location to the end, within every bound.
     *
     * @throws FileNotFoundException on a 404, which is absence rather than failure
     * @throws IOException on any other non-200, a refused connection, a timeout, or a body past
     *     the ceiling
     */
    public byte[] readRemote(final URL url) throws IOException {
        final URLConnection connection = openBounded(url);
        final HttpURLConnection http = connection instanceof HttpURLConnection h ? h : null;
        final long deadline = System.nanoTime() + this.timeout.toNanos();
        try {
            if (http != null) {
                final int status = http.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    // the one status that is absence rather than failure
                    throw new FileNotFoundException("%s answered 404".formatted(this.describe.get()));
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "%s answered HTTP %d, not 200".formatted(this.describe.get(), status));
                }
            }
            try (InputStream in = connection.getInputStream()) {
                return readBounded(in, deadline);
            }
        } catch (final IOException e) {
            // drain first: an undrained error body keeps the socket out of the keep-alive pool. A
            // socket we abandoned mid-response cannot be reused, so that one is closed instead
            release(http, !(e instanceof SocketTimeoutException));
            throw e;
        }
    }

    /**
     * A location with any embedded {@code user:token@} removed; safe to log.
     *
     * <p>The one copy of this rule. It lived twice, privately, in the two sources that read
     * through this class, and a third site ({@code DiscoveryConfig.endpoint()}) had none at all
     * and interpolated the raw URL into its failure message — repeated on every poll, because
     * every fetch re-derives the endpoint. Here rather than in either source because the
     * {@code describe} parameter above is documented as "the location with credentials redacted",
     * so the contract and the one thing that satisfies it live together.</p>
     *
     * <p>Applies to a failure message too, not only to a bare location: {@code URISyntaxException}
     * quotes the whole offending URL in its own message, so a message built from one has to be
     * redacted before it is logged.</p>
     */
    public static String redacted(final String location) {
        return location == null ? null : USERINFO.matcher(location).replaceAll("$1***@");
    }

    /** Reads an already-open local stream to the end, with the ceiling but no deadline. */
    public byte[] readLocal(final InputStream in) throws IOException {
        return readBounded(in, null);
    }

    /**
     * Opens the connection this class is willing to read from. Public so the timeouts a
     * production constructor applies are observable without a ten-second test, including from a
     * caller's own package-private test hook that delegates to this method.
     */
    public URLConnection openBounded(final URL url) throws IOException {
        final URLConnection connection = url.openConnection();
        // left alone entirely when nothing is configured, so an unset key is indistinguishable from
        // how this read behaved before the key existed
        final SSLSocketFactory factory = this.trust.socketFactory();
        if (factory != null && connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(factory);
        }
        connection.setConnectTimeout(timeoutMillis());
        connection.setReadTimeout(timeoutMillis());
        // the content hash decides whether anything is rebuilt, so a cached response would only
        // hide a change from it
        connection.setUseCaches(false);
        // asked here, once per connection, so a credential is as fresh as this read
        final Map<String, String> requestHeaders = Objects.requireNonNull(
                this.headers.get(), "the header supplier returned null; it must return a map, empty if there is nothing to send");
        requestHeaders.forEach(connection::setRequestProperty);
        return connection;
    }

    /**
     * Reads to the end, refusing to grow past the ceiling and to keep reading past {@code deadline}
     * — a {@code System.nanoTime()} reading, or {@code null} for a local read, which has no peer to
     * stall on. Boxed rather than sentinelled because {@code nanoTime()} may legitimately be
     * negative, so no {@code long} value is free to mean "none".
     */
    private byte[] readBounded(final InputStream in, final Long deadline) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        while (true) {
            if (deadline != null && System.nanoTime() - deadline > 0) {
                throw new SocketTimeoutException("%s did not finish responding within %s"
                        .formatted(this.describe.get(), this.timeout));
            }
            final int read = in.read(buffer);
            if (read < 0) {
                return out.toByteArray();
            }
            if (out.size() + read > this.maxBytes) {
                throw new IOException("%s is larger than the %d byte ceiling for a %s"
                        .formatted(this.describe.get(), this.maxBytes, this.subject));
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
     * The timeout as {@code URLConnection} wants it. Clamped rather than thrown: an operator-sized
     * {@code Duration} beyond 24 days is absurd, but turning it into an {@code ArithmeticException}
     * on the poll thread would be worse than treating it as the longest timeout the API can express.
     */
    private int timeoutMillis() {
        return (int) Math.min(this.timeout.toMillis(), Integer.MAX_VALUE);
    }
}
