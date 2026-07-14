/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import java.util.Objects;

/**
 * The four identity dimensions stamped onto every persisted flow by the collecting
 * riptide process: {@code tenant}, {@code organisation}, {@code zone} (the isolated
 * network) and {@code system} (per-instance provenance). Resolved once at startup from
 * {@code riptide.identity.*} and carried through {@link Source} to the persisted row.
 */
public record Identity(String tenant, String organisation, String zone, String system) {
    public Identity {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(organisation);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(system);
    }
}
