/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import org.riptide.config.BoundedHttpRead;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Reads exporter identities from NetBox's own device API, so a site that cannot install a NetBox
 * plugin can still use discovery.
 *
 * <p><b>It emits the same intermediate the plugin does.</b> Each device becomes a target group whose
 * target is the device name and whose labels are the ones {@link ExporterRenderer} already reads.
 * Everything downstream is then shared and already proven: the address rule and its strict parser
 * gate, the skip-and-count for a device with no usable address, both collision refusals, the
 * identical-pair de-duplication, the empty-result refusal and deterministic ordering. A source that
 * rendered its own tree would need a copy of every one of them.</p>
 *
 * <p><b>The prefix length is stripped here, and this is not tidying.</b> A device's primary address
 * arrives as {@code 10.0.0.1/24}. Probed against the loader, that is refused outright for having
 * host bits set; normalising it to the covered block instead is worse and silent, because the entry
 * then matches every host in the subnet and one device's name is attributed to all of them. The
 * plugin strips it server-side for the same reason.</p>
 *
 * <p><b>What it reads, and what it does not.</b> {@code name}, {@code primary_ip4} and
 * {@code primary_ip6}, the last two as nested objects carrying an {@code address}, plus
 * {@code tags} for its slugs, joined into one label. It does not read {@code role} or
 * {@code status}: those matter only inside an operator's filter, which NetBox itself evaluates.
 * Worth saying because NetBox has moved those names before — {@code device_role} was removed from
 * the device serializer in 4.0 while the component endpoints kept it — so a filter written
 * against an older NetBox can stop matching without anything here changing.</p>
 */
public final class NetboxDeviceSource implements DiscoverySource {

    /** The label the renderer reads for an exporter's name; one value, defined where it is read. */
    static final String NAME_LABEL = ExporterRenderer.NAME_LABEL;

    /** The labels the renderer reads for an address, in the order its default consults them. */
    static final String IPV4_LABEL = "__meta_netbox_primary_ip4";
    static final String IPV6_LABEL = "__meta_netbox_primary_ip6";

    /** The device's tag slugs, comma-joined; absent when the device has no tags or no slugs. */
    static final String TAGS_LABEL = "__meta_netbox_tags";

    /**
     * The address labels this source emits, which the renderer must be reading for any device to
     * resolve. {@code DiscoveryConfiguration} refuses a customised {@code address-labels} against
     * this source rather than letting every device silently fail its address check.
     */
    static final List<String> EMITTED_ADDRESS_LABELS = List.of(IPV4_LABEL, IPV6_LABEL);

    /**
     * The bound on one walk, which lives on the shared walk with the origin refusal. Restated here
     * as a constant so the name stays greppable from this source's own tests.
     */
    static final int MAX_DEVICES = PagedJsonWalk.MAX_DEVICES;

    private final PagedJsonWalk walk;
    private final Supplier<String> describe;

    public NetboxDeviceSource(final URL first,
                              final PagedJsonWalk.PageReader pages,
                              final Supplier<String> describe) {
        this.describe = Objects.requireNonNull(describe, "describe");
        this.walk = new PagedJsonWalk(first, pages, this.describe);
    }


    /**
     * The first page's URL: the configured endpoint, the operator's filter, and a stable ordering.
     *
     * <p>The joining happens once, here, because it is the kind of string work that is wrong in a
     * different way at every call site. The filter is the operator's own query text, so its
     * {@code =} and {@code &} are meaningful and must not be escaped; a leading {@code ?} or
     * {@code &} is tolerated because pasting one is natural.</p>
     *
     * <p><b>The URL is assembled from raw text and parsed once.</b> The multi-argument {@link URI}
     * constructor cannot be used here: it treats {@code %} as illegal in a query and quotes it, so a
     * filter copied out of NetBox's own address bar, which is what the documentation tells operators
     * to do, has {@code name=core%20switch} turned into {@code name=core%2520switch}. NetBox then
     * filters on the literal text {@code core%20switch}, matches nothing, and boot fails with
     * "yielded no exporter entries" while the operator's filter demonstrably worked in the browser.
     * A single space is the one character encoded here, because pasting one is common and it is
     * illegal unescaped; everything else is the operator's own already-escaped query text.</p>
     *
     * <p>The ordering is appended unless the filter sets its own. Without it NetBox pages by offset
     * over an unordered result, so a device added mid-walk shifts every remaining page and one
     * device can be returned twice or missed. Ordering by a stable key makes an insert append.</p>
     */
    static URL firstPage(final URL endpoint, final String filter) {
        return firstPage(endpoint, filter, true);
    }

    /**
     * The same joining without NetBox's ordering, for a source whose endpoint is not NetBox.
     *
     * <p>{@code ordering=id} is a NetBox query term and a NetBox concern: it exists so that paging
     * by offset over an unordered result cannot return a device twice. Sent to an arbitrary endpoint
     * it is at best ignored, and at worst a 400 from an API that rejects unknown parameters or a
     * silent re-sort by a field that happens to share the name. The joining itself is shared because
     * it is the kind of string work that is wrong in a different way at every call site (#800).</p>
     */
    static URL firstPage(final URL endpoint, final String filter, final boolean ordered) {
        return firstPage(endpoint, filter, ordered, DiscoveryUrlSet.URL_PROPERTY);
    }

    /**
     * The same joining for one of several endpoints.
     *
     * @param key the property holding {@code endpoint}, named when the join fails, since only that
     *     entry failed: {@code riptide.discovery.url} or {@code riptide.discovery.urls[i]}
     */
    static URL firstPage(final URL endpoint, final String filter, final boolean ordered, final String key) {
        final StringBuilder query = new StringBuilder(endpoint.getQuery() == null ? "" : endpoint.getQuery());
        final String terms = normalise(filter);
        if (!terms.isEmpty()) {
            append(query, terms);
        }
        if (ordered && !hasOrdering(query.toString())) {
            append(query, "ordering=id");
        }
        try {
            // getQuery() on a URL is already the raw form, so escapes the operator configured
            // survive; single-argument URI parses without re-encoding what is here
            // no bare '?' when nothing was joined: a gateway, a strict router or a signed-request
            // proxy can treat '/api/devices?' differently from '/api/devices', and with the NetBox
            // ordering gone there is now a path where the query really is empty
            return new URI(endpoint.getProtocol() + "://" + endpoint.getAuthority()
                    + endpoint.getPath() + (query.isEmpty() ? "" : "?" + query)).toURL();
        } catch (final URISyntaxException | IOException e) {
            throw new IllegalStateException(
                    "%s and riptide.discovery.filter do not combine into a usable URL: '%s' + '%s'"
                            .formatted(key, BoundedHttpRead.redacted(endpoint.toString()), filter), e);
        }
    }

    /**
     * Whether the query already sets an ordering, matched at a term boundary. A substring test is
     * satisfied by {@code cf_ordering=x}, which would silently drop the stable ordering and leave
     * pagination able to return a device twice or miss one.
     */
    private static boolean hasOrdering(final String query) {
        return query.startsWith("ordering=") || query.contains("&ordering=");
    }

    private static void append(final StringBuilder query, final String terms) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(terms);
    }

    /** The operator's filter with a pasted leading separator removed and spaces made safe. */
    private static String normalise(final String filter) {
        if (filter == null || filter.isBlank()) {
            return "";
        }
        String terms = filter.strip();
        while (terms.startsWith("?") || terms.startsWith("&")) {
            terms = terms.substring(1).strip();
        }
        return terms.replace(" ", "%20");
    }

    @Override
    public List<TargetGroup> targets() throws IOException {
        return this.walk.walk(this::results, NetboxDeviceSource::nextLink, this::group);
    }

    /** A device as the renderer wants it, or empty when it carries nothing usable as a name. */
    private java.util.Optional<TargetGroup> group(final JsonNode device) {
        final String name = text(device, "name");
        if (name == null || name.isBlank()) {
            // no name at all: the renderer would have nothing to key an entry on. Devices without a
            // usable ADDRESS are left to the renderer, which skips and counts them like any other
            return java.util.Optional.empty();
        }
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put(NAME_LABEL, name);
        host(device, "primary_ip4").ifPresent(address -> labels.put(IPV4_LABEL, address));
        host(device, "primary_ip6").ifPresent(address -> labels.put(IPV6_LABEL, address));
        tags(device).ifPresent(joined -> labels.put(TAGS_LABEL, joined));
        return java.util.Optional.of(new TargetGroup(List.of(name), labels));
    }

    /** The device's tag slugs, comma-joined; empty when it has none or none carry a slug. */
    private static java.util.Optional<String> tags(final JsonNode device) {
        final JsonNode tags = device.get("tags");
        if (tags == null || !tags.isArray() || tags.isEmpty()) {
            return java.util.Optional.empty();
        }
        final String joined = StreamSupport.stream(tags.spliterator(), false)
                .map(tag -> tag.path("slug").asText(null))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(joined);
    }

    /**
     * A primary address reduced to its host, with any prefix length removed.
     *
     * <p>NetBox nests the address object, so this reads {@code primary_ip4.address} rather than a
     * bare string, and returns empty when the device has none.</p>
     */
    private static java.util.Optional<String> host(final JsonNode device, final String field) {
        final JsonNode primary = device.get(field);
        if (primary == null || primary.isNull()) {
            return java.util.Optional.empty();
        }
        final String address = text(primary, "address");
        if (address == null || address.isBlank()) {
            return java.util.Optional.empty();
        }
        final int slash = address.indexOf('/');
        return java.util.Optional.of(slash < 0 ? address : address.substring(0, slash));
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    /** The page's devices. NetBox wraps them in a paging envelope, unlike the plugin's bare array. */
    private List<JsonNode> results(final JsonNode body) {
        final JsonNode results = body == null ? null : body.get("results");
        if (results == null || !results.isArray()) {
            throw new IllegalStateException(
                    ("%s did not answer with a NetBox device page. Expected an object carrying a 'results' "
                            + "array; a bare array is the Prometheus service discovery shape, which is "
                            + "riptide.discovery.type 'prometheus-sd' rather than 'netbox-api'.")
                            .formatted(this.describe.get()));
        }
        final List<JsonNode> devices = new ArrayList<>();
        results.forEach(devices::add);
        return devices;
    }

    /** Where NetBox puts the link to the next page. The walk decides whether to follow it. */
    private static String nextLink(final JsonNode body) {
        final JsonNode link = body.get("next");
        return link == null || link.isNull() || !link.isTextual() ? null : link.textValue();
    }
}
