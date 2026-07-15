/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

@SpringBootTest
public class FlowTimeoutTest {

    @Autowired
    @Qualifier("netflow9ValueConversionService")
    private ValueConversionService valueConversionService;

    @Test
    void verifyWithoutTimeout() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        raw.FIRST_SWITCHED = Duration.ofMillis(123000);
        raw.LAST_SWITCHED = Duration.ofMillis(987000);

        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));

    }

    @Test
    void fallsBackToExportTimeWhenNoTimingExported() {
        // A template without FIRST_SWITCHED/LAST_SWITCHED (or flowStart/EndMilliseconds): previously
        // getFirstSwitched/getLastSwitched returned null and the flow failed the non-nullable insert.
        // We fall back to the header (export) time, distinct from the receipt time here, matching goflow2.
        final var headerTime = Instant.ofEpochSecond(1000);
        final var received = Instant.ofEpochSecond(5000);
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = headerTime;
        raw.sysUpTime = Duration.ZERO;

        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(received, raw);
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(headerTime);
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(headerTime);
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(headerTime);
    }

    @Test
    void verifyWithActiveTimeout() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        raw.FIRST_SWITCHED = Duration.ofMillis(123_000);
        raw.LAST_SWITCHED = Duration.ofMillis(987_000);
        raw.IN_BYTES = 10L;
        raw.IN_PKTS = 10L;
        raw.FLOW_ACTIVE_TIMEOUT = Duration.ofSeconds(10);
        raw.FLOW_INACTIVE_TIMEOUT = Duration.ofSeconds(300);

        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 10000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void verifyWithInactiveTimeout() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        raw.FIRST_SWITCHED = Duration.ofMillis(123000);
        raw.LAST_SWITCHED = Duration.ofMillis(987000);
        raw.IN_BYTES = 0L;
        raw.IN_PKTS = 0L;
        raw.FLOW_ACTIVE_TIMEOUT = Duration.ofSeconds(10);
        raw.FLOW_INACTIVE_TIMEOUT = Duration.ofSeconds(300);

        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 300000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }
}
