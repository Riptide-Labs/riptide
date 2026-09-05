/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.ProtocolValue;

import java.util.Objects;
import java.util.Set;

public class ProtocolMatcher implements Matcher {

    private final Set<Integer> protocols;

    public ProtocolMatcher(final ProtocolValue protocols) {
        this.protocols = Objects.requireNonNull(protocols.getProtocols());
    }

    public ProtocolMatcher(final String protocols) {
        this(ProtocolValue.of(protocols));
    }

    /**
     * A request with no protocol matches no rule that names one.
     * <p>
     * A protocol number riptide does not map arrives here as a null protocol, because
     * {@code Protocols.getProtocol(Integer)} answers null for it exactly as it does for a flow with no
     * protocol at all. That is a designed state, not a broken request: {@code Threshold.Protocol.compare}
     * answers {@code Order.NA} for it and the decision tree routes on that. This leaf follows the same
     * semantics rather than dereferencing.
     */
    @Override
    public boolean matches(final ClassificationRequest request) {
        final var protocol = request.getProtocol();
        return protocol != null && protocols.contains(protocol.getDecimal());
    }
}
