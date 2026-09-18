/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which source {@code riptide.discovery.type} selects.
 *
 * <p><b>Why these names.</b> The obvious pair would have been "prometheus-sd" and "netbox", and the
 * second would have been wrong: <em>both</em> sources read NetBox in the deployments this feature
 * was built for. One reads it through a plugin that speaks the Prometheus service discovery format,
 * the other through NetBox's own API. So the names say what is being spoken to, not which product
 * is behind it: one is a format that any producer can emit, the other is one product's API.</p>
 */
public enum DiscoverySourceType {

    /**
     * A Prometheus HTTP service discovery document. On NetBox this needs the
     * {@code netbox-plugin-prometheus-sd} plugin installed; any other producer of that format works
     * too. The default, so an existing deployment behaves as it did before this key existed.
     */
    PROMETHEUS_SD("prometheus-sd"),

    /** NetBox's own device API, which needs nothing installed on the NetBox side. */
    NETBOX_API("netbox-api"),

    /**
     * Any JSON endpoint, read by paths the operator writes. The escape hatch for a source of truth
     * that is neither NetBox nor a producer of the service discovery format, so that a site does not
     * have to run a shim to be discovered.
     */
    MAPPED_JSON("mapped-json");

    private final String key;

    DiscoverySourceType(final String key) {
        this.key = key;
    }

    /** The value an operator writes. */
    public String key() {
        return this.key;
    }

    /**
     * The type an operator named.
     *
     * @throws IllegalStateException naming the key, the offending value and every accepted value.
     *     A source that quietly fell back to the default would be one an operator could not tell
     *     they had mistyped, and the symptom would be discovery reading the wrong endpoint shape.
     */
    public static DiscoverySourceType parse(final String value) {
        return Arrays.stream(values())
                .filter(type -> type.key.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "riptide.discovery.type is not a source this collector knows: '%s'. Accepted values are %s."
                                .formatted(value, Arrays.stream(values())
                                        .map(type -> "'" + type.key + "'")
                                        .collect(Collectors.joining(", ")))));
    }
}
