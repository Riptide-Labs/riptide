/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
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
import org.riptide.flows.parser.ipfix.IpfixRawFlow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueConversionServiceTest {

    // The full production visitor set: validate() requires a fitting visitor for every field type on
    // the target, so a trimmed list would fail construction before the conversion under test runs.
    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(),
            new DoubleVisitor(),
            new DurationVisitor(),
            new InetAddressVisitor(),
            new InstantVisitor(),
            new IntegerVisitor(),
            new LongVisitor(),
            new StringVisitor(),
            new UnsignedLongVisitor());

    /**
     * recordCount is a primitive {@code int} field. Visitors are keyed by their boxed target class, so
     * apply() must box the field type before looking one up. When it did not, every primitive-typed
     * field threw an NPE that was swallowed and logged per value, leaving the field unset.
     */
    @Test
    void convertsIntoPrimitiveField() throws Exception {
        final var service = new ValueConversionService(IpfixRawFlow.class, VISITORS);
        final var flow = new IpfixRawFlow();

        final Value<?> recordCount = UnsignedValue.parserWith32Bit("recordCount", null, null)
                .parse(null, Unpooled.buffer(4).writeInt(7));
        service.apply(recordCount, flow);

        assertEquals(7, flow.recordCount);
    }

    /** A boxed field converted through the same path, to show both sides of the boxing behave. */
    @Test
    void convertsIntoBoxedField() throws Exception {
        final var service = new ValueConversionService(IpfixRawFlow.class, VISITORS);
        final var flow = new IpfixRawFlow();

        final Value<?> octetTotalCount = UnsignedValue.parserWith64Bit("octetTotalCount", null, null)
                .parse(null, Unpooled.buffer(8).writeLong(42));
        service.apply(octetTotalCount, flow);

        assertEquals(42L, flow.octetTotalCount);
    }
}
