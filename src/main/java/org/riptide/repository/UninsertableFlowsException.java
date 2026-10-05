/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;

import java.util.List;
import java.util.Map;

/**
 * Some flows of a {@link FlowRepository#persist} call were left out of the insert because the
 * repository could tell beforehand that they cannot be stored (#985).
 *
 * <p><b>Thrown after the rest of the call was inserted</b>, so it reports a partial success and
 * not a refused batch: every flow that is not in {@link #rejected()} is stored. A caller charges
 * and keeps the rejected flows only.
 */
public class UninsertableFlowsException extends FlowException {

    private final transient Map<String, List<EnrichedFlow>> rejected;

    /**
     * @param rejected the flows left out, grouped by the reason each was left out for
     */
    public UninsertableFlowsException(final Map<String, List<EnrichedFlow>> rejected) {
        super(String.join("; ", rejected.keySet()));
        this.rejected = Map.copyOf(rejected);
    }

    /** The flows left out of the insert, by reason. */
    public Map<String, List<EnrichedFlow>> rejected() {
        return this.rejected;
    }

    /** How many flows were left out. */
    public int count() {
        return this.rejected.values().stream().mapToInt(List::size).sum();
    }
}
