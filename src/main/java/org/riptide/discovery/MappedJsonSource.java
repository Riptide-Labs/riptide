/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Any JSON endpoint, read by paths an operator wrote.
 *
 * <p><b>Why this exists.</b> The other two sources read one format and one product's API. A site
 * whose source of truth is a home-grown asset database, a CMDB or a DCIM that is not NetBox had to
 * run a shim that re-emits one of those, which is a service to deploy, monitor and keep in step
 * with two APIs, in exchange for reading two fields out of JSON the site already serves (#800).</p>
 *
 * <p><b>It emits what every source emits.</b> The address becomes the target and the name becomes
 * the label the renderer reads, which is how generic service discovery producers already behave.
 * So no address-label configuration is involved, and everything downstream applies unchanged: the
 * strict address parse, skip-and-count for a device with no usable address, both collision
 * refusals, identical-pair de-duplication, the empty-result refusal and deterministic ordering.</p>
 *
 * <p><b>The mapping is paths and nothing else</b>, for the reasons {@link JsonPath} gives. The one
 * shape that costs is an address carrying a prefix length, which cannot be mapped and which this
 * source therefore names: without that, such an endpoint skips every device and reports yielding
 * nothing, which reads as a filter or permission mistake and sends an operator to the wrong
 * place.</p>
 */
public final class MappedJsonSource implements DiscoverySource {

    /** How many prefixed addresses the diagnostic names before it stops listing them. */
    private static final int NAMED_EXAMPLES = 3;

    private final PagedJsonWalk walk;
    private final MappingPaths paths;
    private final Supplier<String> describe;

    /** The four paths, resolved once so a mistake in one is a startup failure rather than a poll one. */
    record MappingPaths(JsonPath items, JsonPath name, JsonPath address, JsonPath next) {
    }

    public MappedJsonSource(final URL first,
                            final PagedJsonWalk.PageReader pages,
                            final Supplier<String> describe,
                            final MappingPaths paths) {
        this.describe = Objects.requireNonNull(describe, "describe");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.walk = new PagedJsonWalk(first, pages, this.describe);
    }

    @Override
    public List<TargetGroup> targets() throws IOException {
        final List<String> prefixed = new ArrayList<>();
        final List<TargetGroup> groups =
                this.walk.walk(this::devices, this::nextLink, device -> group(device, prefixed));
        if (groups.isEmpty() && !prefixed.isEmpty()) {
            // the one failure the path language cannot fix, said plainly. Otherwise every device is
            // skipped and the shared renderer reports yielding nothing, which names a filter
            throw new IllegalStateException(
                    ("%s mapped %d address(es) that carry a prefix length, which cannot be used as an "
                            + "exporter address: %s. The path language has no transform to remove it "
                            + "(riptide.discovery.mapping.address = '%s'). Serve the address without the "
                            + "prefix, or use riptide.discovery.type 'netbox-api' if this is NetBox.")
                            .formatted(this.describe.get(), prefixed.size(),
                                    String.join(", ", prefixed.subList(0, Math.min(NAMED_EXAMPLES, prefixed.size()))),
                                    this.paths.address()));
        }
        return groups;
    }

    /** The devices in one response, wherever the operator said they are. */
    private List<JsonNode> devices(final JsonNode body) {
        final JsonNode at = this.paths.items().node(body).orElse(null);
        if (at == null || !at.isArray()) {
            throw new IllegalStateException(
                    ("%s did not answer with an array at riptide.discovery.mapping.items = '%s': found %s. "
                            + "The path names where the devices are in the response.")
                            .formatted(this.describe.get(), this.paths.items(), found(at)));
        }
        final List<JsonNode> devices = new ArrayList<>();
        at.forEach(devices::add);
        return devices;
    }

    private static String found(final JsonNode at) {
        if (at == null) {
            return "nothing";
        }
        return at.isObject() ? "an object" : at.getNodeType().toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** Where the link to the next page is, or nothing when the operator configured no paging. */
    private String nextLink(final JsonNode body) {
        return this.paths.next() == null ? null : this.paths.next().read(body).orElse(null);
    }

    /**
     * A device as the renderer wants it: the address as the target, the name as the label.
     *
     * <p>A device missing either path is skipped rather than refused, exactly as one with no usable
     * address is: an endpoint legitimately carries entries this mapping does not describe, and the
     * shared renderer counts what it skips.</p>
     */
    private Optional<TargetGroup> group(final JsonNode device, final List<String> prefixed) {
        final String name = this.paths.name().read(device).orElse(null);
        final String address = this.paths.address().read(device).orElse(null);
        if (name == null || name.isBlank() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        if (address.indexOf('/') >= 0) {
            // skipped here rather than emitted, which is what the renderer would do with it anyway:
            // the difference is that this source knows WHY, and can say so if it turns out to be why
            // nothing was usable. Remembered, never stripped — stripping is the transform this
            // language does not have, and doing it for one field is how a language arrives without
            // anyone deciding to have one
            prefixed.add(address);
            return Optional.empty();
        }
        return Optional.of(new TargetGroup(List.of(address), Map.of(ExporterRenderer.NAME_LABEL, name)));
    }
}
