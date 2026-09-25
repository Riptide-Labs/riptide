/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Every configured discovery endpoint with the source that reads it, in configured order.
 *
 * <p>One bean holding the list rather than one bean per endpoint: nothing outside discovery
 * injects a single client or source, and {@code ComposedInventoryDocument} needs to know which
 * endpoint a group, a failure or an empty answer came from, which a source concatenating several
 * clients would lose.</p>
 *
 * @param endpoints at least one, in the order an operator listed them
 */
public record DiscoveryEndpoints(List<Endpoint> endpoints) {

    public DiscoveryEndpoints {
        endpoints = List.copyOf(endpoints);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one discovery endpoint");
        }
    }

    /**
     * One endpoint and its source.
     *
     * @param describe the endpoint's redacted spelling, the name every message about it uses
     * @param source reads this endpoint alone
     */
    public record Endpoint(Supplier<String> describe, DiscoverySource source) {

        public Endpoint {
            Objects.requireNonNull(describe, "describe");
            Objects.requireNonNull(source, "source");
        }
    }
}
