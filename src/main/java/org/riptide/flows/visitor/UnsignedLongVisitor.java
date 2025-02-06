package org.riptide.flows.visitor;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.values.UnsignedValue;

public class UnsignedLongVisitor implements ValueVisitor<UnsignedLong> {
    @Override
    public Class<UnsignedLong> targetClass() {
        return UnsignedLong.class;
    }

    @Override
    public UnsignedLong visit(UnsignedValue value) {
        return value.getValue();
    }
}
