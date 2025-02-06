package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.StringValue;

public class StringVisitor implements ValueVisitor<String> {
    @Override
    public String visit(StringValue value) {
        return value.getValue();
    }
}
