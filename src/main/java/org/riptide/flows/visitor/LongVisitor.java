package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

public class LongVisitor implements ValueVisitor<Long> {
    @Override
    public Long visit(UnsignedValue value) {
        return value.getValue().longValue();
    }

    @Override
    public Long visit(SignedValue value) {
        return value.getValue();
    }
}
