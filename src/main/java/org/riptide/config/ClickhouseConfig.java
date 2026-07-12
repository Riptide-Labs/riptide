/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.clickhouse")
public final class ClickhouseConfig {
        private String endpoint = "http://localhost:8123";

        private String username;
        private String password;

        private String database = "riptide";
}
