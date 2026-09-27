/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic exporter discovery from a Prometheus HTTP service discovery endpoint.
 *
 * <p>An unset or blank {@code riptide.discovery.url}, with no non-blank entry in
 * {@code riptide.discovery.urls}, disables the feature entirely, which is why
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
     * Several endpoints read under this one configuration, for a source of truth that serves its
     * exporters from more than one path: NetBox answers devices and virtual machines separately.
     * Every entry shares {@link #type}, {@link #filter}, {@link #token}, {@link #authScheme} and
     * {@link #mapping}. Exclusive with {@link #url}; {@link #endpoints()} refuses both.
     *
     * <p>A list next to {@code url} rather than a list-valued {@code url}: Spring splits a scalar on
     * commas, so a URL with a comma in its query would be torn in two. The indexed environment form
     * {@code RIPTIDE_DISCOVERY_URLS_0} keeps such a URL whole.</p>
     */
    private List<String> urls;

    /**
     * Which source to read, as one of {@link DiscoverySourceType}'s keys. Unset means the
     * Prometheus service discovery reader, so a deployment that predates this key behaves exactly
     * as it did. This chooses HOW discovery reads, for every endpoint; whether it runs at all is
     * {@link #url} or {@link #urls} alone.
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

        /**
         * Where the array of devices is in the response, e.g. {@code results}. Unset means the
         * response is itself the array, which is a shape no path can name.
         */
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
     * A NetBox tag slug. A discovered device carrying it is composed with {@code poll: always}.
     * Unset means no device is marked. Read by the exporter renderer, which matches it against the
     * {@code __meta_netbox_tags} label, not against the source. netbox-api emits that label from a
     * device's tag slugs. A prometheus-sd document that carries it is honoured the same way.
     * mapped-json never carries it, so its entries always stay {@code poll: on-flow}.
     */
    private String pollAlwaysTag;

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
     * The configured endpoints in order, each with the key it was written under. This is the one
     * place the two endpoint keys are validated; the gate, the beans and the composition all read
     * this list rather than a rule of their own.
     *
     * <p>{@code url} alone is one endpoint named {@code riptide.discovery.url}, exactly as before
     * {@code urls} existed. With neither key set the same single entry is returned unset, which
     * only a caller outside the {@link DiscoveryUrlSet} gate can reach.</p>
     *
     * @throws IllegalStateException when both keys are set, naming both, or when a non-empty
     *     {@code urls} holds a blank entry, naming {@code riptide.discovery.urls[i]}
     */
    public List<DiscoveryEndpoint> endpoints() {
        final boolean urlSet = StringUtils.hasText(this.url);
        if (urlSet && hasEntry(this.urls)) {
            throw new IllegalStateException(
                    "%s and %s are both set. Set one of them: %s for a single endpoint, %s for several."
                            .formatted(DiscoveryUrlSet.URL_PROPERTY, DiscoveryUrlSet.URLS_PROPERTY,
                                    DiscoveryUrlSet.URL_PROPERTY, DiscoveryUrlSet.URLS_PROPERTY));
        }
        if (urlSet || !hasEntry(this.urls)) {
            return List.of(new DiscoveryEndpoint(DiscoveryUrlSet.URL_PROPERTY, this.url));
        }
        final List<DiscoveryEndpoint> endpoints = new ArrayList<>(this.urls.size());
        for (int i = 0; i < this.urls.size(); i++) {
            final String key = "%s[%d]".formatted(DiscoveryUrlSet.URLS_PROPERTY, i);
            if (!StringUtils.hasText(this.urls.get(i))) {
                // refused rather than skipped: a blank entry between two URLs is a list an
                // operator edited by hand and got wrong, and skipping it would drop whatever
                // endpoint they meant to write there without a word
                throw new IllegalStateException(
                        ("%s is blank. Once %s holds an entry, every entry must be a usable URL: "
                                + "remove the blank one.").formatted(key, DiscoveryUrlSet.URLS_PROPERTY));
            }
            endpoints.add(new DiscoveryEndpoint(key, this.urls.get(i)));
        }
        return List.copyOf(endpoints);
    }

    /**
     * Whether a bound {@code urls} list counts as set: at least one non-blank entry. An empty list,
     * or a list of blanks only, is unset, the rule {@link #url} follows for an exported-but-empty
     * variable. One copy, read by both {@link #endpoints()} and the {@link DiscoveryUrlSet} gate, so
     * the two cannot disagree on {@code RIPTIDE_DISCOVERY_URLS=""}.
     */
    static boolean hasEntry(final List<String> urls) {
        return urls != null && urls.stream().anyMatch(StringUtils::hasText);
    }

    /**
     * The key that turned discovery on, for the sentences that tell an operator which key to
     * unset: {@code riptide.discovery.urls} when the list holds an entry and {@code url} does not,
     * otherwise {@code riptide.discovery.url}.
     */
    public String enablingKey() {
        // read off the list endpoints() built, so which key counts stays decided in one place
        return endpoints().getFirst().key().startsWith(DiscoveryUrlSet.URLS_PROPERTY)
                ? DiscoveryUrlSet.URLS_PROPERTY
                : DiscoveryUrlSet.URL_PROPERTY;
    }

    /**
     * {@code riptide.discovery.url} as a {@link URL}; see {@link DiscoveryEndpoint#endpoint()}.
     * The single-endpoint spelling, kept for callers that predate {@link #endpoints()}.
     */
    public URL endpoint() {
        return new DiscoveryEndpoint(DiscoveryUrlSet.URL_PROPERTY, this.url).endpoint();
    }

    /** {@code riptide.discovery.url}, redacted; see {@link DiscoveryEndpoint#describe()}. */
    public String describe() {
        return new DiscoveryEndpoint(DiscoveryUrlSet.URL_PROPERTY, this.url).describe();
    }
}
