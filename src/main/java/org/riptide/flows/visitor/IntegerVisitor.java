package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.springframework.stereotype.Service;

@Service
public class IntegerVisitor implements ValueVisitor<Integer> {
    @Override
    public Class<Integer> targetClass() {
        return Integer.class;
    }
    @Override
    public Integer visit(SignedValue value) {
        return value.getValue().intValue();
    }

    @Override
    public Integer visit(UnsignedValue value) {
        return value.getValue().intValue();
    }
}
