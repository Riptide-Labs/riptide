/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The original source: a Prometheus HTTP service discovery document, which on NetBox means the
 * {@code netbox-plugin-prometheus-sd} plugin is installed.
 *
 * <p>It is a thin pairing of the fetch with the parse. Both halves already existed; this type only
 * gives them a name so a second source can sit beside them.</p>
 */
public final class ServiceDiscoverySource implements DiscoverySource {

    /** What a fetch answers with; an interface so tests need no HTTP server. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch() throws IOException;
    }

    private final Fetcher fetcher;
    private final Supplier<String> describe;

    public ServiceDiscoverySource(final Fetcher fetcher, final Supplier<String> describe) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.describe = Objects.requireNonNull(describe, "describe");
    }

    @Override
    public List<TargetGroup> targets() throws IOException {
        return ServiceDiscoveryParser.parse(this.fetcher.fetch(), this.describe.get());
    }
}
