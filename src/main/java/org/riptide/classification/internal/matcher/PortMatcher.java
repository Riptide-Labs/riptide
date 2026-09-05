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
     * {@inheritDoc}
     * <p>
     * Without the guard the extracted {@code Integer} auto-unboxes into {@link PortValue#matches(int)}.
     * No bundled rule was measured to reach this with a null, but the ruleset is operator-supplied and
     * {@link ProtocolMatcher#matches(ClassificationRequest)} is the same defect where it is reachable.
     */
    @Override
    public boolean matches(ClassificationRequest request) {
        final Integer port = valueExtractor.apply(request);
        return port != null && this.value.matches(port);
    }
}
