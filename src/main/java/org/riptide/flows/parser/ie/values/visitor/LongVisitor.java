package org.riptide.flows.parser.ie.values.visitor;

import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.springframework.stereotype.Service;

@Service
public class LongVisitor implements ValueVisitor<Long> {
    @Override
    public Class<Long> targetClass() {
        return Long.class;
    }

    @Override
    public Long visit(UnsignedValue value) {
        return value.getValue().longValue();
    }

    @Override
    public Long visit(SignedValue value) {
        return value.getValue();
    }
}
