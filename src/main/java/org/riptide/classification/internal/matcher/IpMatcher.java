/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import java.util.Objects;
import java.util.function.Function;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.IpAddr;
import org.riptide.classification.internal.value.IpValue;

class IpMatcher implements Matcher {

    private final Function<ClassificationRequest, IpAddr> valueExtractor;

    private final IpValue value;

    protected IpMatcher(final IpValue input,
                        final Function<ClassificationRequest, IpAddr> valueExtractor) {
        this.value = Objects.requireNonNull(input);
        this.valueExtractor = Objects.requireNonNull(valueExtractor);
    }

    /**
     * A request with no address in this direction matches no rule that names one.
     * <p>
     * Same semantics as {@code Threshold.Address.compare}, which answers {@code Order.NA} for an absent
     * address and lets the decision tree route on it. The extractor yields an {@code IpAddr}, so this
     * takes {@code IpValue.isInRange(IpAddr)} and not the {@code String} overload with its
     * {@code Objects.requireNonNull}; without the guard the null reached {@code IpRange.contains}, where
     * comparing against it unboxes the other address. See {@code ProtocolMatcher.matches} for the
     * reachable form of the same defect.
     */
    @Override
    public boolean matches(ClassificationRequest request) {
        final IpAddr address = valueExtractor.apply(request);
        return address != null && value.isInRange(address);
    }
}
