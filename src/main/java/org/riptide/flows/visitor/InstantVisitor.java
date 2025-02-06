package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.DateTimeValue;

import java.time.Instant;

public class InstantVisitor implements TheVisitor<Instant> {
    @Override
    public Instant visit(DateTimeValue value) {
        return value.getValue();
    }
}
