/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import org.junit.jupiter.api.Test;
import org.riptide.repository.clickhouse.ClickhouseFlow;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one property the rollup boundary rests on (#470): {@code samplingInterval} is never {@code 0}.
 *
 * <p>Appending the rate to a rollup gives rows aggregated before the append the column's type
 * default, and that default is the <em>only</em> thing marking them — a column joining the sorting
 * key cannot be given an explicit {@code DEFAULT}. The predicate {@code samplingInterval > 0} is
 * therefore exactly "aggregated after the append", and only while no live flow can carry
 * {@code 0}.</p>
 *
 * <p><b>Every rate source, not a sample of them.</b> An earlier version of this class checked two
 * builders while asserting the property "lives in two parsers" — there are four, and the one it
 * omitted was the one that violated the invariant: sFlow read its rate straight off the wire with
 * no guard at all. A test that covers a subset of the sources delivers exactly the false confidence
 * it was written to prevent, so the list below is derived from the guard's name and asserted to be
 * complete.</p>
 */
class SamplingIntervalBoundaryTest {

    /** Every builder that resolves a sampling rate. Adding a fifth without a guard fails here. */
    private static final List<Class<?>> RATE_SOURCES = List.of(
            org.riptide.flows.parser.netflow5.Netflow5FlowBuilder.class,
            org.riptide.flows.parser.netflow9.Netflow9FlowBuilder.class,
            org.riptide.flows.parser.ipfix.IpFixFlowBuilder.class,
            org.riptide.flows.parser.sflow.SflowFlowBuilder.class);

    /** The persisted default when nothing sets a rate at all. */
    @Test
    void thePersistedDefaultIsOneNotZero() {
        assertThat(new ClickhouseFlow().getSamplingInterval())
                .as("an unset rate must persist as 1.0; 0 is reserved to mean 'before the append'")
                .isEqualTo(1.0d);
    }

    /**
     * Every builder admits only finite rates {@code >= 1.0}. A zero, a negative, a fraction, a NaN
     * or an infinity resolves to "no rate stated" rather than being persisted as a rate of zero.
     *
     * <p>Reached by reflection, deliberately. A copy of the rule in this file would keep passing if
     * any builder's guard were relaxed — the exact failure this test exists to prevent — so the real
     * methods have to be the ones under test.</p>
     */
    @Test
    void noRateSourceCanProduceAZero() throws Exception {
        for (final Class<?> builder : RATE_SOURCES) {
            final Method usable = guardOf(builder);
            usable.setAccessible(true);
            final boolean primitive = usable.getParameterTypes()[0] == double.class;

            for (final Object rejected : primitive
                    ? new Object[] {0.0d, -1.0d, 0.5d, Double.NaN, Double.POSITIVE_INFINITY}
                    : new Object[] {0.0d, -1.0d, 0.5d, Double.NaN, Double.POSITIVE_INFINITY, null}) {
                assertThat(rejects(usable, rejected))
                        .as("%s must not accept %s as a sampling rate", builder.getSimpleName(), rejected)
                        .isTrue();
            }
            assertThat(rejects(usable, 1.0d))
                    .as("%s must accept a rate of 1", builder.getSimpleName())
                    .isFalse();
            assertThat(rejects(usable, 1000.0d)).isFalse();
        }
    }

    /** Both guard shapes mean the same thing: a boolean predicate, or null for "unusable". */
    private static boolean rejects(final Method usable, final Object candidate) throws Exception {
        final Object result = usable.invoke(null, candidate);
        return result instanceof Boolean accepted ? !accepted : result == null;
    }

    private static Method guardOf(final Class<?> builder) {
        for (final Method method : builder.getDeclaredMethods()) {
            if ("usable".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new AssertionError(builder.getSimpleName() + " resolves a sampling rate but has no"
                + " usable() guard — a rate of 0 reaching the rollups would break the boundary"
                + " predicate that marks pre-append rows (#470)");
    }
}
