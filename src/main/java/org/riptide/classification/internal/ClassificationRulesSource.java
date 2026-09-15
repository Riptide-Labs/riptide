/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import org.riptide.config.BoundedHttpRead;
import org.riptide.config.ByteOrderMark;
import org.riptide.config.ClassificationConfig;
import org.riptide.config.FileWatchTrigger;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

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

    private final ClassificationConfig config;
    private final BoundedHttpRead http;

    public ClassificationRulesSource(final ClassificationConfig config) {
        this(config, DEFAULT_TIMEOUT);
    }

    /** Visible for tests, which cannot wait out the default timeout on every hung-server row. */
    ClassificationRulesSource(final ClassificationConfig config, final Duration timeout) {
        this.config = Objects.requireNonNull(config);
        // describe() is a supplier because the location is re-read from config on every call, and
        // "ruleset" keeps the ceiling message byte-identical to what ClassificationRuleReloaderTest
        // asserts
        this.http = new BoundedHttpRead(
                Objects.requireNonNull(timeout), MAX_BYTES, "ruleset", this::describe, Map.of());
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

    /**
     * The one read both entry points share: {@link #read()} for boot and the engine's own reload,
     * {@link #fetch()} for the watch loop. A leading UTF-8 BOM is removed here for that reason —
     * stripping it in the watch loop would have missed this path entirely, because
     * {@code ClassificationRuleReloader} discards the bytes it is handed and has the engine re-read
     * through here (#725).
     *
     * <p>Unlike the YAML paths, this one visibly breaks without it: commons-csv does not strip a
     * BOM, so the first header becomes {@code \uFEFFname}, the header comparison in
     * {@code CsvImporter} fails, and its message prints the expected and actual headers with the
     * difference invisible. At boot that is a startup failure.</p>
     */
    private byte[] read(final Resource resource) throws IOException {
        final URL remote = remoteUrl(resource);
        if (remote == null) {
            try (InputStream in = resource.getInputStream()) {
                return ByteOrderMark.strip(this.http.readLocal(in));
            }
        }
        return ByteOrderMark.strip(this.http.readRemote(remote));
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

    /**
     * The location, with any embedded credentials removed; safe to log. The pattern that does the
     * removing lives in {@link BoundedHttpRead#redacted}, which is the one copy of it: it was
     * private here and private again in {@code DiscoveryClient}, which is the shape #561 warns
     * about, and a third site had no copy at all.
     */
    @Override
    public String describe() {
        return BoundedHttpRead.redacted(this.config.getRules().getDescription());
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

    /** Visible for tests: the timeouts this source applies, observable without a ten-second wait. */
    URLConnection openBounded(final URL url) throws IOException {
        return this.http.openBounded(url);
    }
}
