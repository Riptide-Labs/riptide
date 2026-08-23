/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import org.riptide.repository.clickhouse.ClickhouseFlow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one property the rollup boundary rests on (#470): {@code samplingInterval} is never {@code 0}.
 *
 * <p>Appending the rate to a rollup gives rows aggregated before the append the column's type
 * default, and that default is the <em>only</em> thing marking them — a column joining the sorting
 * key cannot be given an explicit {@code DEFAULT}. The predicate {@code samplingInterval > 0} is
 * therefore exactly "aggregated after the append", and only while no live flow can carry {@code 0}.
 *
 * <p>Pinned here rather than assumed, because the property lives in two parsers and a POJO default,
 * and a later change to any of them would silently turn the boundary into a guess.</p>
 */
class SamplingIntervalBoundaryTest {

    /** The persisted default when nothing sets a rate at all. */
    @Test
    void thePersistedDefaultIsOneNotZero() {
        assertThat(new ClickhouseFlow().getSamplingInterval())
                .as("an unset rate must persist as 1.0; 0 is reserved to mean 'before the append'")
                .isEqualTo(1.0d);
    }

    /**
     * Both builders admit only finite rates {@code >= 1.0}. A zero, a negative, a NaN or an
     * infinity from a malformed exporter resolves to "no rate stated" and falls down the ladder,
     * rather than being persisted as a rate of zero.
     *
     * <p>Reached by reflection, deliberately. A copy of the rule in this file would keep passing if
     * either builder's guard were relaxed — which is the failure this test exists to prevent, so
     * the real method has to be the one under test.</p>
     */
    @Test
    void noBuilderCanProduceAZeroRate() throws Exception {
        for (final Class<?> builder : new Class<?>[] {
                org.riptide.flows.parser.netflow5.Netflow5FlowBuilder.class,
                org.riptide.flows.parser.ipfix.IpFixFlowBuilder.class}) {
            final Method usable = builder.getDeclaredMethod("usable", Double.class);
            usable.setAccessible(true);

            for (final Double rejected : new Double[] {0.0, -1.0, 0.5, Double.NaN,
                    Double.POSITIVE_INFINITY, null}) {
                assertThat(usable.invoke(null, rejected))
                        .as("%s must not accept %s as a sampling rate", builder.getSimpleName(), rejected)
                        .isNull();
            }
            assertThat(usable.invoke(null, 1.0d)).isEqualTo(1.0d);
            assertThat(usable.invoke(null, 1000.0d)).isEqualTo(1000.0d);
        }
    }
}
