/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.value;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.riptide.classification.Protocols;
import org.riptide.classification.Protocol;
import org.riptide.classification.internal.decision.Bound;

public class ProtocolValue {

    /**
     * @throws IllegalArgumentException if any named keyword does not resolve, or if the string
     *     names none at all. Dropping the unresolvable ones used to leave an empty set, which
     *     {@code ProtocolValue.shrink} answers null for, which makes {@code Classifier.of}'s
     *     {@code addMatcher} build no {@code ProtocolMatcher} — so the protocol condition vanished
     *     and the rule matched <em>every</em> protocol (#763). Refusing is the same answer #759
     *     gave: a rule that cannot be honoured as written is rejected in preprocessing, named in
     *     the reloader's WARN, while the rest of the ruleset keeps serving.
     *     <p>One bad keyword refuses the whole rule rather than narrowing it to the ones that did
     *     resolve. Honouring half of {@code tcp,tpc} would be the same class of quiet wrong answer,
     *     and it would hide the typo instead of naming it.</p>
     */
    public static ProtocolValue of(String string) {
        final var named = new StringValue(string).splitBy(",");
        // one lookup per keyword, kept so the unresolvable ones can be named in the message
        final var resolved = new LinkedHashMap<String, Protocol>();
        for (final StringValue keyword : named) {
            resolved.put(keyword.getValue(), Protocols.getProtocol(keyword.getValue()));
        }
        final var unresolvable = resolved.entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("', '"));
        if (!unresolvable.isEmpty()) {
            throw new IllegalArgumentException(
                    ("protocol names '%s', which riptide cannot resolve. A rule naming a protocol it does not know"
                            + " would be applied to every protocol rather than the one it names, so it is refused."
                            + " Name a protocol by keyword, not by number, and leave the column empty to mean"
                            + " any protocol.").formatted(unresolvable));
        }
        if (resolved.isEmpty()) {
            // e.g. "," — splitBy trims and drops empty segments, so there is no keyword to report
            // unresolvable, yet the result is the same empty set that used to drop the condition.
            throw new IllegalArgumentException(
                    ("protocol is set to '%s' but names no protocol at all. Leave the column empty to mean any"
                            + " protocol; as written the rule would be applied to every protocol.").formatted(string));
        }
        return new ProtocolValue(resolved.values().stream()
                .map(Protocol::getDecimal)
                .collect(Collectors.toSet()));
    }

    private final Set<Integer> protocols;

    public ProtocolValue(final Set<Integer> protocols) {
        this.protocols = Objects.requireNonNull(protocols);
    }

    public Set<Integer> getProtocols() {
        return this.protocols;
    }

    /**
     * <b>A null return here is normal and correct</b>, unlike the empty-set case {@code of} refuses.
     * Measured on the bundled ruleset, 320 of 12,498 classifiers reach this and get null.
     *
     * <p>Read that way round because the #763 prose above describes a null from {@code shrink} as
     * the step by which a dropped condition widened a rule, and a reader arriving here would
     * reasonably conclude that fix is incomplete. It is not. The difference is where the emptiness
     * comes from: {@code of} refuses a value that never named a protocol, whereas here the protocol
     * has <em>already been decided</em> by the path through the tree. {@code canBeRestrictedBy} is
     * false only for {@code Bound.Eq}, a rule only lands in a threshold's eq bucket if its set
     * contains that value, and {@code Tree.classify} only enters that subtree when the request's
     * protocol equals it. So dropping the matcher is redundancy elimination, not a lost condition.
     *
     * <p>{@code PortValue.shrink} and {@code IpValue.shrink} never drop this way: they test
     * {@code overlaps} rather than {@code canBeRestrictedBy}. The asymmetry is deliberate.</p>
     */
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
