/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

@SpringBootTest
public class FlowCountersTest {

    @Autowired
    @Qualifier("netflow9ValueConversionService")
    private ValueConversionService valueConversionService;

    @Test
    void permanentCountersUsedWhenInCountersAbsent() {
        // Exporters using permanent-cache counters (field 85/86) send no IN_BYTES/IN_PKTS;
        // such flows used to be persisted with bytes = 0. Drive the name-keyed reflection too.
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        valueConversionService.apply(new UnsignedValue("IN_PERMANENT_BYTES", 4711L), raw);
        valueConversionService.apply(new UnsignedValue("IN_PERMANENT_PKTS", 42L), raw);

        final var flow = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getBytes()).isEqualTo(4711L);
        Assertions.assertThat(flow.getPackets()).isEqualTo(42L);
    }

    @Test
    void inCountersPreferredOverPermanentCounters() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        raw.IN_BYTES = 100L;
        raw.IN_PKTS = 10L;
        raw.IN_PERMANENT_BYTES = 4711L;
        raw.IN_PERMANENT_PKTS = 42L;

        final var flow = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getBytes()).isEqualTo(100L);
        Assertions.assertThat(flow.getPackets()).isEqualTo(10L);
    }
}
