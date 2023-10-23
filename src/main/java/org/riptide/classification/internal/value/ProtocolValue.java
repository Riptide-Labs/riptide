package org.riptide.classification.internal.value;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.riptide.classification.Protocols;
import org.riptide.classification.Protocol;
import org.riptide.classification.internal.decision.Bound;

public class ProtocolValue {

    public static ProtocolValue of(String string) {
        var protocols = new StringValue(string).splitBy(",")
                .stream()
                .map(p -> Protocols.getProtocol(p.getValue()))
                .filter(Objects::nonNull)
                .map(Protocol::getDecimal)
                .collect(Collectors.toSet());
        return new ProtocolValue(protocols);
    }

    private final Set<Integer> protocols;

    public ProtocolValue(final Set<Integer> protocols) {
        this.protocols = Objects.requireNonNull(protocols);
    }

    public Set<Integer> getProtocols() {
        return this.protocols;
    }

    public ProtocolValue shrink(final Bound<Integer> bound) {
        Set<Integer> s = new HashSet<>(this.protocols.size());
        for (var i : this.protocols) {
            if (bound.canBeRestrictedBy(i)) {
                s.add(i);
            }
        }
        return s.isEmpty()
                ? null
                : s.size() == this.protocols.size()
                    ? this
                    : new ProtocolValue(s);
    }

}
