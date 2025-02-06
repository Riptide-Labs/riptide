package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

public class IntVisitor implements TheVisitor<Integer> {
    @Override
    public Integer visit(SignedValue value) {
        return value.getValue().intValue();
    }

    @Override
    public Integer visit(UnsignedValue value) {
        return value.getValue().intValue();
    }
}
