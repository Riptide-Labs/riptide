/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.data;

import java.util.Objects;
import org.riptide.flows.parser.data.Flow.SamplingProvenance;

/**
 * A sampling interval and the rung of the resolution ladder that supplied it.
 *
 * <p>The two travel together so a ladder is walked once and cannot report a value from one rung
 * while naming another. Resolving them separately would need a second traversal that drifts from
 * the first on the next edit, and is unsafe in the IPFIX builder regardless: its selector-algorithm
 * rung divides by exporter-supplied ranges and must not be evaluated for a record that already
 * carries its rate.
 *
 * @param interval the resolved rate; {@code 1.0} when {@code from} is
 *                 {@link SamplingProvenance#Assumed}
 * @param from     the rung that produced {@code interval}
 */
public record ResolvedRate(double interval, SamplingProvenance from) {

    public ResolvedRate {
        Objects.requireNonNull(from, "from must not be null");
    }

    /** Nothing stated a rate: {@code 1.0}, recorded as assumed rather than as an answer. */
    public static ResolvedRate assumed() {
        return new ResolvedRate(1.0, SamplingProvenance.Assumed);
    }

    public static ResolvedRate of(final double interval, final SamplingProvenance from) {
        return new ResolvedRate(interval, from);
    }
}
