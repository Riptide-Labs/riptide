/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.riptide.config.BoundedHttpRead;
import org.riptide.config.ByteOrderMark;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.secrets.SecretResolvers;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Fetches a Prometheus HTTP service discovery document, within every bound the shared
 * {@link BoundedHttpRead} applies.
 *
 * <p>The token is resolved twice over, and both times matter. Once in the constructor, purely as a
 * startup gate: an unresolvable reference stops the collector, which is the rule
 * {@code ClickhouseRepository} follows for its credentials and keeps a mistyped reference from
 * becoming a failure repeated every minute forever. Then again on every read, so a rotated token
 * takes effect on the next poll rather than at the next restart (#804); see {@link #headers()}.</p>
 */
public final class DiscoveryClient {

    /**
     * Refusal ceiling for a discovery document. Generous: the NetBox plugin disables pagination, so
     * a large fleet legitimately answers with several megabytes, and the ceiling exists to stop a
     * misdirected endpoint from being read into the heap rather than to bound a real fleet.
     */
    static final int MAX_BYTES = 32 * 1024 * 1024;

    /** Kept on one line so it stays greppable, like {@code DiscoveryUrlSet.URL_PROPERTY}. */
    static final String AUTH_SCHEME_PROPERTY = "riptide.discovery.auth-scheme";

    private final DiscoveryConfig config;
    private final SecretResolvers secretResolvers;
    private final BoundedHttpRead http;

    public DiscoveryClient(final DiscoveryConfig config, final SecretResolvers secretResolvers) {
        this(config, secretResolvers, new OutboundHttpTrust());
    }

    public DiscoveryClient(final DiscoveryConfig config,
                           final SecretResolvers secretResolvers,
                           final OutboundHttpTrust trust) {
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(secretResolvers, "secretResolvers");
        // resolved here as a startup gate, and thrown away: per-read resolution must not turn a
        // mistyped reference into a failure repeated every poll for the life of the process, which
        // is the reason this was resolved once in the first place
        final String token = secretResolvers.resolve(config.getToken());
        // refused here, not silently prefixed: an exported-but-empty variable is exactly the shape
        // the URL gate defends against, and a blank scheme sends "Authorization: <space><token>",
        // which NetBox answers 403 to. The operator then sees only "answered HTTP 403, not 200",
        // with nothing anywhere naming the scheme.
        //
        // gated on the SAME condition that builds the header, not on the key alone: with no token
        // the scheme is never read, so refusing a blank one would kill a collector over a key that
        // does nothing — trading one exported-but-empty startup failure for another, which is the
        // class of defect this guard exists to remove
        if (token != null && (config.getAuthScheme() == null || config.getAuthScheme().isBlank())) {
            throw new IllegalStateException(
                    ("%s must not be blank when %s is set: the scheme is prefixed to the token, so a "
                            + "blank one sends an Authorization header with no scheme and the endpoint "
                            + "rejects it. Set it (NetBox wants 'Token'), or leave the key unset to get "
                            + "that default.").formatted(AUTH_SCHEME_PROPERTY, "riptide.discovery.token"));
        }
        this.secretResolvers = secretResolvers;
        this.http = new BoundedHttpRead(
                config.getTimeout(), MAX_BYTES, "discovery document", this::describe, this::headers, trust);
    }

    /**
     * The request headers for one read, with the token resolved now rather than at startup.
     *
     * <p><b>Why per read.</b> Resolved once, rotating the token needed a restart, and nothing said
     * so. That contradicts what the reference schemes promise elsewhere here: a {@code file://}
     * reference is re-read on every use for a device credential, so an operator who rotates a
     * file-backed discovery token reasonably expects the same (#804). The cost is one resolver call
     * per poll, which is a file read or an environment lookup for every scheme but {@code vault://},
     * and one Vault read a minute at the default interval.</p>
     *
     * <p><b>What it must never do is fall back.</b> A reference that stops resolving throws, which
     * fails the poll and leaves the last good inventory serving. Sending the request without the
     * header instead would let an endpoint that answers an unauthenticated read publish a fleet the
     * operator never authorised this collector to see, and every guard downstream would read that
     * as a legitimate change.</p>
     */
    private Map<String, String> headers() {
        final String token = this.secretResolvers.resolve(this.config.getToken());
        return token == null
                ? Map.of()
                : Map.of("Authorization", this.config.getAuthScheme() + " " + token);
    }

    /**
     * One page from an explicit URL, for a source that pages. Same bounds, same headers and same
     * 404-is-absence contract as {@link #fetch()}; only the address differs, because NetBox hands
     * the next page's link back in the body rather than letting a caller construct it.
     */
    public byte[] fetchPage(final java.net.URL page) throws IOException {
        return ByteOrderMark.strip(this.http.readRemote(page));
    }

    /**
     * The document as the endpoint has it now, with any byte-order mark removed.
     *
     * @throws java.io.FileNotFoundException on a 404, which is absence rather than failure
     * @throws IOException on any other non-200, a refused connection, a timeout, or a body past the
     *     ceiling
     */

    public byte[] fetch() throws IOException {
        return ByteOrderMark.strip(this.http.readRemote(this.config.endpoint()));
    }

    /**
     * The endpoint, with any embedded credentials removed; safe to log. One line of delegation
     * rather than a second copy of the redaction: {@code DiscoveryConfig.endpoint()} needs the
     * same redacted spelling for its own failure message, and two copies of one rule is the
     * shape this project keeps being bitten by.
     */
    public String describe() {
        return this.config.describe();
    }
}
