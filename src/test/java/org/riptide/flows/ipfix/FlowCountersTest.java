/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

@SpringBootTest
public class FlowCountersTest {
    @Autowired
    @Qualifier("ipfixValueConversionService")
    private ValueConversionService conversionService;

    @Test
    void totalCountersUsedWhenDeltasAbsent() {
        // Juniper SRX inline J-Flow exports octetTotalCount/packetTotalCount (IE 85/86)
        // instead of the delta variants; such flows used to be persisted with bytes = 0.
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.octetTotalCount = 4711L;
        raw.packetTotalCount = 42L;

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getBytes()).isEqualTo(4711L);
        Assertions.assertThat(flow.getPackets()).isEqualTo(42L);
    }

    @Test
    void totalCountersArePopulatedByName() {
        // The raw-flow fields are filled via reflection keyed on the IE name from the IANA
        // registry — a typo in the field name would silently no-op, so drive the real wiring.
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        conversionService.apply(new UnsignedValue("octetTotalCount", 4711L), raw);
        conversionService.apply(new UnsignedValue("packetTotalCount", 42L), raw);

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getBytes()).isEqualTo(4711L);
        Assertions.assertThat(flow.getPackets()).isEqualTo(42L);
    }

    @Test
    void siblingTotalCountersArePopulatedByName() {
        // The post/layer2 totals are last in the fallback chains; each must reach its field
        // through the same name-keyed reflection.
        for (final var name : new String[]{"postOctetTotalCount", "layer2OctetTotalCount", "postLayer2OctetTotalCount"}) {
            final var raw = new IpfixRawFlow();
            raw.exportTime = Instant.EPOCH;
            conversionService.apply(new UnsignedValue(name, 4711L), raw);
            conversionService.apply(new UnsignedValue("postPacketTotalCount", 42L), raw);

            final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

            Assertions.assertThat(flow.getBytes()).as(name).isEqualTo(4711L);
            Assertions.assertThat(flow.getPackets()).as("postPacketTotalCount").isEqualTo(42L);
        }
    }

    @Test
    void deltaCountersPreferredOverTotals() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.octetDeltaCount = 100L;
        raw.packetDeltaCount = 10L;
        raw.octetTotalCount = 4711L;
        raw.packetTotalCount = 42L;

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getBytes()).isEqualTo(100L);
        Assertions.assertThat(flow.getPackets()).isEqualTo(10L);
    }
}
