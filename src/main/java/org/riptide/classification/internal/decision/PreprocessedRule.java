/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.riptide.classification.IpAddr;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.value.IpValue;
import org.riptide.classification.internal.value.PortValue;
import org.riptide.classification.internal.value.ProtocolValue;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bundles a rule with derived information. Improves tree construction performance.
 */
public class PreprocessedRule {

    /**
     * Derives a rule's five value fields, refusing a rule that cannot be honoured as written.
     *
     * <p>This is the seam every rule crosses whatever provided it, so it is where a rule is
     * refused. There are two reasons, and the value parsers below add the second:</p>
     * <ul>
     *   <li>the rule carries an exporter filter, which nothing matches on (#759);</li>
     *   <li>a condition column names something unresolvable &mdash; an unknown protocol keyword, or
     *       a protocol, port or address column that is non-empty but names nothing. Those throw
     *       from {@code ProtocolValue.of}, {@code PortValue.of} and {@code IpValue.of} (#763).</li>
     * </ul>
     *
     * @throws IllegalArgumentException in either case. Nothing matches on
     *     that field — this method derives the five value fields below and drops it, so
     *     {@code Classifier.of} builds no matcher for it — which meant a rule naming one exporter
     *     was evaluated as though it had named none, and applied to every exporter (#759). It is
     *     refused here rather than at import because this is the seam every rule crosses whatever
     *     provided it, and because {@code DefaultClassificationEngine.reload} turns a throw from
     *     here into one rejected rule that the reloader names in a WARN while the rest of the
     *     ruleset keeps serving. That is the posture the operator docs already promise for a rule
     *     the engine cannot use; aborting the whole ruleset would break it, and would fail the
     *     boot outright, since the rules are loaded eagerly at startup.
     */
    public static PreprocessedRule of(final Rule rule) {
        if (rule.hasExporterFilterDefinition()) {
            throw new IllegalArgumentException(
                    "exporterFilter is not implemented: no matcher evaluates it, so this rule would be applied to"
                            + " every exporter rather than the one it names. Leave the column empty. See issue 759.");
        }
        return new PreprocessedRule(rule,
                rule.hasProtocolDefinition() ? ProtocolValue.of(rule.getProtocol()) : null,
                rule.hasSrcPortDefinition() ? PortValue.of(rule.getSrcPort()) : null,
                rule.hasDstPortDefinition() ? PortValue.of(rule.getDstPort()) : null,
                rule.hasSrcAddressDefinition() ? IpValue.of(rule.getSrcAddress()) : null,
                rule.hasDstAddressDefinition() ? IpValue.of(rule.getDstAddress()) : null
        );
    }

    private static Stream<Threshold> protocolThresholds(ProtocolValue value) {
        return value == null ? Stream.empty() : value.getProtocols().stream().map(Threshold.Protocol::new);
    }

    private static Stream<Threshold> portThresholds(
            PortValue value,
            Function<Integer, Threshold> thresholdCreator
    ) {
        return value == null ? Stream.empty() : value
                .getPortRanges()
                .stream()
                .flatMap(range -> Stream.of(range.getBegin(), range.getEnd()))
                .map(thresholdCreator);
    }

    private static Stream<Threshold> addressThresholds(
            IpValue value,
            Function<IpAddr, Threshold> thresholdCreator
    ) {
        return value == null ? Stream.empty() : value
                .getIpAddressRanges()
                .stream()
                .flatMap(range -> Stream.of(range.begin, range.end))
                .map(thresholdCreator);
    }

    public final Rule rule;

    // if a rule does not specify a criteria for some aspect then the corresponding ProtocolValue, PortValue, or IpValue is null
    public final ProtocolValue protocol;
    public final PortValue srcPort, dstPort;
    public final IpValue srcAddr, dstAddr;

    // candidate thresholds derived from the rules values
    public final Set<Threshold> thresholds;

    public PreprocessedRule(Rule rule, ProtocolValue protocol, PortValue srcPort, PortValue dstPort, IpValue srcAddr, IpValue dstAddr) {
        this.rule = rule;
        this.protocol = protocol;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.srcAddr = srcAddr;
        this.dstAddr = dstAddr;
        this.thresholds = Stream.of(
                protocolThresholds(protocol),
                portThresholds(srcPort, Threshold.SrcPort::new),
                portThresholds(dstPort, Threshold.DstPort::new),
                addressThresholds(srcAddr, Threshold.SrcAddress::new),
                addressThresholds(dstAddr, Threshold.DstAddress::new)
        ).flatMap(Function.identity()).collect(Collectors.toSet());
    }

    public Classifier createClassifier(Bounds bounds) {
        return Classifier.of(this, bounds);
    }

    public PreprocessedRule reverse() {
        return new PreprocessedRule(
                rule.reversedRule(),
                protocol,
                dstPort,
                srcPort,
                dstAddr,
                srcAddr
        );
    }

}
