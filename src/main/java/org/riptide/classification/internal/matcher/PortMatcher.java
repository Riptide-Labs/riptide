/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import java.util.Objects;
import java.util.function.Function;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.PortValue;

class PortMatcher implements Matcher {

    private final Function<ClassificationRequest, Integer> valueExtractor;

    private final PortValue value;

    protected PortMatcher(final PortValue ports,
                          final Function<ClassificationRequest, Integer> valueExtractor) {
        this.value = Objects.requireNonNull(ports);
        this.valueExtractor = Objects.requireNonNull(valueExtractor);
    }

    /**
     * A request with no port in this direction matches no rule that names one.
     * <p>
     * Same semantics as {@code Threshold.Port.compare}, which answers {@code Order.NA} for an absent
     * port and lets the decision tree route on it. Without the guard the extracted {@code Integer}
     * auto-unboxes into {@code PortValue.matches(int)}; see {@code ProtocolMatcher.matches} for the
     * reachable form of the same defect.
     */
    @Override
    public boolean matches(ClassificationRequest request) {
        final Integer port = valueExtractor.apply(request);
        return port != null && this.value.matches(port);
    }
}
