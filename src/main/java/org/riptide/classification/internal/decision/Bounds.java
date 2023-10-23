package org.riptide.classification.internal.decision;

import org.riptide.classification.IpAddr;

/**
 * Bundles bounds for the different aspects of flows that are used for classification.
 * <p>
 * Bounds are used during decision tree construction to filter candidate thresholds and classification rules.
 */
public class Bounds {

    public static Bounds ANY = new Bounds(Bound.any(), Bound.any(), Bound.any(), Bound.any(), Bound.any());

    public final Bound<Integer> protocol;
    public final Bound<Integer> srcPort, dstPort;
    public final Bound<IpAddr> srcAddr, dstAddr;

    public Bounds(final Bound<Integer> protocol,
                  final Bound<Integer> srcPort,
                  final Bound<Integer> dstPort,
                  final Bound<IpAddr> srcAddr,
                  final Bound<IpAddr> dstAddr) {
        this.protocol = protocol;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.srcAddr = srcAddr;
        this.dstAddr = dstAddr;
    }
}
