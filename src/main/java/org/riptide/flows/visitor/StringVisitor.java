package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.StringValue;
import org.springframework.stereotype.Service;

@Service
public class StringVisitor implements ValueVisitor<String> {
    @Override
    public Class<String> targetClass() {
        return String.class;
    }
    @Override
    public String visit(StringValue value) {
        return value.getValue();
    }
}
