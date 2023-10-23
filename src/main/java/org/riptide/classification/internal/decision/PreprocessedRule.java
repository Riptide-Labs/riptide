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

    public static PreprocessedRule of(final Rule rule) {
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
