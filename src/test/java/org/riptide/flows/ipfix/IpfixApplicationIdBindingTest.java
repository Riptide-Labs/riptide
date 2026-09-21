/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The typed value binds by name to the raw flow, and the flow reports it packed, 0 when absent. */
class IpfixApplicationIdBindingTest {

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private final ValueConversionService conversion = new ValueConversionService(IpfixRawFlow.class, VISITORS);

    @Test
    void applicationIdBindsToTheRawFlow() {
        final var raw = new IpfixRawFlow();

        this.conversion.apply(new UnsignedValue("applicationId", 0x03000050L), raw);

        assertThat(raw.applicationId).isEqualTo(0x03000050L);
    }

    @Test
    void aBoundIdIsReportedByTheFlow() {
        final var raw = new IpfixRawFlow();
        raw.applicationId = 0x0d0001dfL;

        final Flow flow = new IpFixFlowBuilder(this.conversion).buildFlow(Instant.EPOCH, raw);

        assertThat(flow.getApplicationId()).isEqualTo(0x0d0001dfL);
    }

    @Test
    void anAbsentIdReadsAsZero() {
        final Flow flow = new IpFixFlowBuilder(this.conversion).buildFlow(Instant.EPOCH, new IpfixRawFlow());

        assertThat(flow.getApplicationId()).isZero();
    }
}
