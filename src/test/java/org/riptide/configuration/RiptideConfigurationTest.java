/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RiptideConfigurationTest {

    @Test
    void verifyNetflow9ConversionService(@Qualifier("netflow9ValueConversionService") ValueConversionService service) {
        Assertions.assertThat(service.targetType).isEqualTo(Netflow9RawFlow.class);
    }

    @Test
    void verifyIpfixConversionService(@Qualifier("ipfixValueConversionService") ValueConversionService service) {
        Assertions.assertThat(service.targetType).isEqualTo(IpfixRawFlow.class);
    }

    @Test
    void theApplicationRegistryCarriesTheRuntimeSeries(@Autowired MetricRegistry registry) {
        // The one registry /metrics renders; a JVM series registered anywhere else is never scraped.
        Assertions.assertThat(registry.getNames()).contains("jvm.heap.used", "jvm.gc.seconds", "jvm.threads.live");
    }
}