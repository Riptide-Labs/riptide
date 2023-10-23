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
