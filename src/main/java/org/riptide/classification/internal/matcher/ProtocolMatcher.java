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
     * {@inheritDoc}
     * <p>
     * This is the only one of the three leaf matchers reachable with the ruleset riptide ships. A null
     * protocol reaches it whenever a flow carries an IP protocol number riptide does not map:
     * {@code Protocols.getProtocol(Integer)} answers null for those. It is the only way a null protocol
     * arises from the wire, because {@code Flow.getProtocol()} is a primitive that both the v9 and IPFIX
     * builders default to 0, which is HOPOPT and mapped. 148-252 are unmapped in {@code Protocols}.
     */
    @Override
    public boolean matches(final ClassificationRequest request) {
        final var protocol = request.getProtocol();
        return protocol != null && protocols.contains(protocol.getDecimal());
    }
}
