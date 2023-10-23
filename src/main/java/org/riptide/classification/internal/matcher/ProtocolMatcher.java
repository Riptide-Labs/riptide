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

    @Override
    public boolean matches(final ClassificationRequest request) {
        return protocols.contains(request.getProtocol().getDecimal());
    }
}
