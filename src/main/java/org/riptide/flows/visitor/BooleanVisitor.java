package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.BooleanValue;

public class BooleanVisitor implements ValueVisitor<Boolean> {
    @Override
    public Class<Boolean> targetClass() {
        return Boolean.class;
    }

    @Override
    public Boolean visit(BooleanValue value) {
        return value.getValue();
    }
}
