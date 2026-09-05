/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.value;

import org.riptide.classification.internal.decision.Bound;

import java.util.ArrayList;
import java.util.List;

public class PortValue implements RuleValue<Integer, PortValue> {

    public static PortValue of(String input) {
            final StringValue portValue = new StringValue(input);
            if (portValue.hasWildcard()) {
                throw new IllegalArgumentException("Wildcards not supported");
            }
            final List<StringValue> portValues = portValue.splitBy(",");
            List<IPPortRange> ranges = new ArrayList<>();
            for (var pv: portValues) {
                if (pv.isRanged()) {
                    var rv = new RangedValue(pv);
                    ranges.add(new IPPortRange(rv.getStart(), rv.getEnd()));
                } else {
                    var iv = new IntegerValue(pv);
                    ranges.add(new IPPortRange(iv.getValue()));
                }
            }
            if (ranges.isEmpty()) {
                // The same shape #763 fixed for protocol, reached the same way: splitBy trims and
                // drops empty segments, so "," yields no ranges at all. An empty range list makes
                // shrink() answer null, which makes Classifier.of's addMatcher build no
                // PortMatcher — so the port condition is dropped and the rule matches every port.
                // Measured before this guard: a rule with dstPort="," classified TCP/9999.
                throw new IllegalArgumentException(
                        ("port is set to '%s' but names no port at all. Leave the column empty to mean any port;"
                                + " as written the rule would be applied to every port.").formatted(input));
            }
            return new PortValue(ranges);
    }

    private final List<IPPortRange> ranges;

    public PortValue(List<IPPortRange> ranges) {
        this.ranges = ranges;
    }

    public List<IPPortRange> getPortRanges() {
        return ranges;
    }

    public boolean matches(int port) {
        for (var r: ranges) {
            if (r.contains(port)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PortValue shrink(Bound<Integer> bound) {
        List<IPPortRange> l = new ArrayList<>(ranges.size());
        for (var r: ranges) {
            if (bound.overlaps(r.getBegin(), r.getEnd())) {
                l.add(r);
            }
        }
        return l.isEmpty() ? null : ranges.size() == l.size() ? this : new PortValue(l);
    }

}
