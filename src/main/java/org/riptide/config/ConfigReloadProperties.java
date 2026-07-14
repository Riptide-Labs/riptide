/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config hot-reload knobs. Reloading is opt-in: with no interval (or zero), the
 * external config file is bound once at boot and never watched.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.config")
public class ConfigReloadProperties {

    /** Poll interval for the external config file; absent or zero disables reloading. */
    private Duration reloadInterval = Duration.ZERO;
}
