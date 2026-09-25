/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.riptide.config.BoundedHttpRead;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * One configured discovery endpoint and the key an operator wrote it under, either
 * {@code riptide.discovery.url} or one entry {@code riptide.discovery.urls[i]}. The key travels with
 * the value so every refusal about this endpoint names the line the operator has to edit.
 *
 * @param key the property that holds this endpoint, e.g. {@code riptide.discovery.urls[1]}
 * @param url the configured value, possibly carrying {@code user:token@}
 */
public record DiscoveryEndpoint(String key, String url) {

    /**
     * The endpoint as a {@link URL}.
     *
     * @throws IllegalStateException naming the key and the value, rather than letting a binder
     *     stack trace reach an operator who wrote one bad character. The value is the redacted
     *     one from {@link #describe()}, and so is the parser's own complaint: an operator who
     *     wrote {@code https://svc:token@netbox/...} would otherwise have that token logged at
     *     startup and again on every poll, because every fetch re-derives the endpoint.
     */
    public URL endpoint() {
        try {
            return new URI(this.url).toURL();
        } catch (final MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            // both halves are redacted, not just the first: URISyntaxException quotes the whole
            // offending URL in its own message, so the credential would come back through the
            // parenthesis that was meant to explain what was wrong with it
            final String reason = BoundedHttpRead.redacted(e.getMessage());
            throw new IllegalStateException(
                    "%s is not a usable URL: '%s' (%s)".formatted(this.key, describe(), reason),
                    e instanceof MalformedURLException ? e : new MalformedURLException(reason));
        }
    }

    /**
     * The endpoint with any embedded {@code user:token@} removed; safe to log, and the one
     * spelling of how this endpoint is named. {@code DiscoveryClient.describe()} delegates here
     * rather than holding a second copy, so the failure above and every fetch-failure message
     * name the endpoint identically.
     */
    public String describe() {
        return this.url == null ? this.key + " (unset)" : BoundedHttpRead.redacted(this.url);
    }
}
