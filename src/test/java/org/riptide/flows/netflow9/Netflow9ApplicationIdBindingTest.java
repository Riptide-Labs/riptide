/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;

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
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The v9 sibling of the IPFIX binding test: the typed value binds by name, the flow reports it packed, 0 when absent. */
class Netflow9ApplicationIdBindingTest {

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private final ValueConversionService conversion = new ValueConversionService(Netflow9RawFlow.class, VISITORS);

    private static Netflow9RawFlow raw() {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.EPOCH;
        raw.sysUpTime = Duration.ZERO;
        return raw;
    }

    @Test
    void applicationIdBindsToTheRawFlow() {
        final var raw = raw();

        this.conversion.apply(new UnsignedValue("applicationId", 0x0300007bL), raw);

        assertThat(raw.applicationId).isEqualTo(0x0300007bL);
    }

    @Test
    void aBoundIdIsReportedByTheFlow() {
        final var raw = raw();
        raw.applicationId = 0x01000001L;

        final Flow flow = new Netflow9FlowBuilder(this.conversion).buildFlow(Instant.EPOCH, raw);

        assertThat(flow.getApplicationId()).isEqualTo(0x01000001L);
    }

    @Test
    void anAbsentIdReadsAsZero() {
        final Flow flow = new Netflow9FlowBuilder(this.conversion).buildFlow(Instant.EPOCH, raw());

        assertThat(flow.getApplicationId()).isZero();
    }
}
