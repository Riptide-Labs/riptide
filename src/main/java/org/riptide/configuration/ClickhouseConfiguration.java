/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import org.riptide.config.ClickhouseConfig;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.riptide.secrets.SecretResolvers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickhouseConfiguration {
    @Bean
    public ClickhouseRepository clickhouseRepository(final ClickhouseRepository.FlowMapper flowMapper,
                                                     final ClickhouseConfig config,
                                                     final SecretResolvers secretResolvers) {
        return new ClickhouseRepository(flowMapper, config, secretResolvers);
    }
}
