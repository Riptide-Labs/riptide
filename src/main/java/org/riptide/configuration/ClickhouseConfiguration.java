/*
 * Copyright 2025 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import org.riptide.config.ClickhouseConfig;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickhouseConfiguration {
    @Bean
    public ClickhouseRepository clickhouseRepository(final ClickhouseRepository.FlowMapper flowMapper, final ClickhouseConfig config) {
        return new ClickhouseRepository(flowMapper, config);
    }
}
