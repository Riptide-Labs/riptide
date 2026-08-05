/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Management HTTP server (health endpoints) configuration. */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.management")
public class RiptideManagementProperties {

    /** Serve the management HTTP endpoints ({@code /livez}, {@code /readyz}). */
    private boolean enabled = true;

    /** Management HTTP port. */
    private int port = 8080;

    /** Bind address; defaults to all interfaces so a kubelet can probe the pod IP. */
    private String bindAddress = "0.0.0.0";

    /**
     * Ceiling on probes handled at once; anything beyond it is answered 503 rather than queued.
     *
     * <p>A thread-per-task executor has no ceiling of its own, and this port listens on all
     * interfaces by default, so without a cap anything that can reach it can make the collector
     * hold one virtual thread, one exchange and one socket per concurrent request. The handlers
     * only read in-memory state, so this is far above what a kubelet or a Compose healthcheck will
     * ever need — it exists to bound abuse, not to shape normal traffic.
     */
    private int maxConcurrentRequests = 32;
}
