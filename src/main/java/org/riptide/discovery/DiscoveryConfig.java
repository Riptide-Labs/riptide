/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import lombok.Data;
import org.riptide.config.BoundedHttpRead;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;

/**
 * Dynamic exporter discovery from a Prometheus HTTP service discovery endpoint.
 *
 * <p>An unset or blank {@code riptide.discovery.url} disables the feature entirely, which is why
 * there is no separate enable flag: a key whose only job is to say that another key means it is a
 * key that can disagree with itself. Whether discovery runs at all is decided by
 * {@link DiscoveryUrlSet}, the {@code Condition} on {@code DiscoveryConfiguration}, which is the
 * single gate: a second {@code isEnabled()} method here would be the same rule encoded twice, and
 * the two would disagree on a present-but-blank value.</p>
 *
 * <p>There is deliberately no {@code type} key. Adding one later with a default preserving this
 * behaviour is not a breaking change, so it buys nothing today (#799).</p>
 */
@Data
@ConfigurationProperties(prefix = "riptide.discovery")
public class DiscoveryConfig {

    /**
     * The service discovery endpoint. Unset or blank disables discovery. Blank counts because a
     * container image or a Helm template exports every variable it knows about, value or not, and
     * {@code RIPTIDE_DISCOVERY_URL=""} must not turn a feature on for an operator who never asked
     * for it and then refuse to boot.
     */
    private String url;

    /**
     * Which source to read, as one of {@link DiscoverySourceType}'s keys. Unset means the
     * Prometheus service discovery reader, so a deployment that predates this key behaves exactly
     * as it did. This chooses HOW discovery reads; whether it runs at all is {@link #url} alone.
     *
     * <p>A String rather than the enum so an unrecognised value fails with a message this project
     * writes, naming the key, the value and what is accepted, instead of a binder stack trace. The
     * same reason {@link #url} is a String.</p>
     */
    private String type;

    /**
     * Credential for the endpoint, as a secret reference like every other credential here. NetBox
     * wants its API token; a producer needing no authentication leaves this unset.
     */
    private SecretRef token;

    /**
     * The {@code Authorization} scheme paired with {@link #token}. NetBox expects
     * {@code Authorization: Token <key>}, which is why this is not {@code Bearer} by default.
     *
     * <p>Unlike {@link #url}, blank is not "unset" here when a {@link #token} is set:
     * {@code DiscoveryClient} refuses it at construction, naming the key. A blank scheme is prefixed
     * to the token anyway, and the header it produces is rejected by the endpoint with nothing in
     * the failure naming the scheme. With no token the scheme is never read, so a blank one is
     * harmless and does not fail startup — refusing it there would kill a collector over a key that
     * does nothing, which is the same exported-but-empty trap the refusal exists to close.</p>
     */
    private String authScheme = "Token";

    /**
     * Poll interval for the endpoint. A minute rather than the file watcher's interval because the
     * NetBox service discovery plugin disables pagination and has no entity tag support, so every
     * poll transfers a full serialization of every visible device.
     */
    private Duration interval = Duration.ofSeconds(60);

    /** Bounds the connect, each read, and the whole response. */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * Narrows what the source returns, in the endpoint's own query terms, so a large inventory is
     * not fetched whole on every poll. Read by the NetBox device source, which appends it to the
     * device request; the Prometheus service discovery reader ignores it, because that endpoint
     * takes its filtering from whatever produced the document.
     *
     * <p>Deliberately NetBox's own vocabulary rather than one invented here, for example
     * {@code status=active&role=leaf&role=spine}. An operator can develop it against NetBox
     * directly and paste in what already works.</p>
     */
    private String filter;

    /**
     * Where the fields are, for {@code riptide.discovery.type} 'mapped-json'. Read by
     * {@code DiscoveryConfiguration}, which turns them into the paths {@code MappedJsonSource} uses
     * and refuses at startup when a required one is missing.
     */
    private Mapping mapping = new Mapping();

    /** The paths a mapped-json source reads. Dotted field names; see {@code JsonPath}. */
    @lombok.Data
    public static class Mapping {

        /** Where the array of devices is in the response, e.g. {@code results}. Required. */
        private String items;

        /** Where the exporter name is in one device, e.g. {@code name}. Required. */
        private String name;

        /** Where the exporter address is in one device, e.g. {@code primary_ip.address}. Required. */
        private String address;

        /** Where the link to the next page is, e.g. {@code next}. Unset means a single request. */
        private String next;
    }

    /**
     * Labels consulted in order for an entry's address, first one present wins. The default pair is
     * what the NetBox service discovery plugin emits; every IP it emits already has its CIDR mask
     * stripped. When no label in this list is present the target itself is used, which is what makes
     * non-NetBox producers work.
     */
    private List<String> addressLabels =
            List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");

    /**
     * The source an operator selected, defaulting to the Prometheus service discovery reader.
     *
     * @throws IllegalStateException naming the key, the value and every accepted value
     */
    public DiscoverySourceType sourceType() {
        return this.type == null || this.type.isBlank()
                ? DiscoverySourceType.PROMETHEUS_SD
                : DiscoverySourceType.parse(this.type);
    }

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
                    "%s is not a usable URL: '%s' (%s)".formatted(DiscoveryUrlSet.URL_PROPERTY, describe(), reason),
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
        return this.url == null ? DiscoveryUrlSet.URL_PROPERTY + " (unset)" : BoundedHttpRead.redacted(this.url);
    }
}
