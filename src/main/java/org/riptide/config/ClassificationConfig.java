/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "riptide.classification")
@Data
public class ClassificationConfig {
    /**
     * The classification rules CSV as a Spring resource location,
     * e.g. "classpath:/classification-rules.csv" or "file:/etc/riptide/rules.csv".
     */
    private Resource rules = new ClassPathResource("classification-rules.csv");
}
