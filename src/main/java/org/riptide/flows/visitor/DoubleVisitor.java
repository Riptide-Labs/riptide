package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.springframework.stereotype.Service;

@Service
public class DoubleVisitor implements ValueVisitor<Double> {
    @Override
    public Class<Double> targetClass() {
        return Double.class;
    }

    @Override
    public Double visit(FloatValue value) {
        return value.getValue();
    }

    @Override
    public Double visit(SignedValue value) {
        return value.getValue().doubleValue();
    }

    @Override
    public Double visit(UnsignedValue value) {
        return value.getValue().doubleValue();
    }
}
