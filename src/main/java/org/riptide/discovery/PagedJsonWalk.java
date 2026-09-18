/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A walk over a paged JSON endpoint: read a page, map its devices, follow the link to the next.
 *
 * <p><b>One implementation, for every source that pages.</b> Two of its rules were added after
 * review and proven by mutation, and both guard against a failure that is invisible from the
 * outcome. A second source restating them would be the fourth place in this collector holding a
 * rule its siblings already hold, and the copy that drifts is the one nobody is looking at
 * (#800).</p>
 *
 * <p><b>The bound counts devices read, not entries emitted.</b> A device a source cannot map still
 * cost a read, so counting emitted entries leaves a fleet whose devices are all unusable, or a
 * {@code next} link cycling back to a page already seen, walking forever with the counter pinned at
 * zero. Exceeding it is refused rather than truncated: a short read is indistinguishable downstream
 * from a fleet that shrank, and the regression guard would either refuse it or publish it and drop
 * exporters that still exist.</p>
 *
 * <p><b>The walk stays on the origin it started from.</b> The credential travels with the client
 * rather than with a URL, so a {@code next} link elsewhere would send it there. An endpoint builds
 * that link from its own request host, which anything setting a wrong forwarded host in front of it
 * can make into another origin entirely.</p>
 *
 * <p>What varies by source stays with the source: where the devices are in a response, where the
 * next link is, and how a device becomes a target group.</p>
 */
final class PagedJsonWalk {

    /**
     * How many devices one walk may gather before it is refused.
     *
     * <p>A count rather than a byte total, deliberately. The per-request byte ceiling already
     * protects the heap against one oversized answer, and what this bound exists for is the other
     * shape: a walk that never ends. An operator can reason about "more devices than I have", and
     * cannot reason about a megabyte figure without knowing the endpoint's serialization size.</p>
     */
    static final int MAX_DEVICES = 100_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reads one page of JSON from a URL; an interface so tests need no HTTP server. */
    @FunctionalInterface
    public interface PageReader {
        byte[] read(URL page) throws IOException;
    }

    private final URL first;
    private final PageReader pages;
    private final Supplier<String> describe;

    PagedJsonWalk(final URL first, final PageReader pages, final Supplier<String> describe) {
        this.first = Objects.requireNonNull(first, "first");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.describe = Objects.requireNonNull(describe, "describe");
    }

    /**
     * Every page, mapped.
     *
     * @param devices the devices in one response, or a throw naming what the shape should have been
     * @param nextLink the link to the next page, or null at the end of the walk
     * @param map one device as a target group, or empty for a device this source cannot use
     * @throws IOException when a page could not be read. A page after the first failing fails the
     *     whole walk rather than rendering from what was gathered: a partial read is
     *     indistinguishable downstream from a fleet that shrank.
     */
    List<TargetGroup> walk(final Function<JsonNode, List<JsonNode>> devices,
                           final Function<JsonNode, String> nextLink,
                           final Function<JsonNode, Optional<TargetGroup>> map) throws IOException {
        final List<TargetGroup> groups = new ArrayList<>();
        URL page = this.first;
        int read = 0;
        while (page != null) {
            final JsonNode body = parse(this.pages.read(page));
            for (final JsonNode device : devices.apply(body)) {
                if (++read > MAX_DEVICES) {
                    throw new IllegalStateException(
                            ("%s returned more than %d devices. Narrow it with riptide.discovery.filter "
                                    + "rather than reading an unbounded inventory on every poll.")
                                    .formatted(this.describe.get(), MAX_DEVICES));
                }
                map.apply(device).ifPresent(groups::add);
            }
            page = next(nextLink.apply(body));
        }
        return List.copyOf(groups);
    }

    private JsonNode parse(final byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (final JacksonException e) {
            throw new IllegalStateException(
                    "%s's response is not valid JSON: %s".formatted(this.describe.get(), e.getOriginalMessage()), e);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "%s could not be read: %s".formatted(this.describe.get(), e.getMessage()), e);
        }
    }

    /** The next page, or null at the end of the walk. */
    private URL next(final String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            final URL next = new URI(link).toURL();
            if (!sameOrigin(this.first, next)) {
                throw new IllegalStateException(
                        ("%s gave a 'next' page on a different origin (%s://%s). The API token is sent "
                                + "with every page, so the walk stops rather than following it. Check "
                                + "whatever sets the forwarded host in front of it.")
                                .formatted(this.describe.get(), next.getProtocol(), next.getAuthority()));
            }
            return next;
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "%s gave a 'next' page link that is not a usable URL: '%s'"
                            .formatted(this.describe.get(), link), e);
        }
    }

    /** Same scheme, host and port, so the credential never leaves the origin it was configured for. */
    private static boolean sameOrigin(final URL first, final URL next) {
        return first.getProtocol().equalsIgnoreCase(next.getProtocol())
                && first.getAuthority().equalsIgnoreCase(next.getAuthority());
    }
}
