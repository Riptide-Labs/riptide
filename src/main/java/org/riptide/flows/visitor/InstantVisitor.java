package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class InstantVisitor implements ValueVisitor<Instant> {

    @Override
    public Class<Instant> targetClass() {
        return Instant.class;
    }

    @Override
    public Instant visit(final DateTimeValue value) {
        return value.getValue();
    }

    public Instant visit(final UnsignedValue value) {
        return switch (value.getUnit().orElse(null)) {
            case "seconds" -> Instant.ofEpochSecond(value.getValue().longValue());
            case "milliseconds" -> Instant.ofEpochMilli(value.getValue().longValue());
            case null, default -> null;
        };
    }

    public Instant visit(final SignedValue value) {
        return switch (value.getUnit().orElse(null)) {
            case "seconds" -> Instant.ofEpochSecond(value.getValue());
            case "milliseconds" -> Instant.ofEpochMilli(value.getValue());
            case null, default -> null;
        };
    }
}
