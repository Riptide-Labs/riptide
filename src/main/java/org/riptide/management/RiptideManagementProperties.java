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
}
