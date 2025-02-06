package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import java.time.Duration;

public class DurationVisitor implements ValueVisitor<Duration> {
    @Override
    public Duration visit(final UnsignedValue value) {
        return switch (value.getUnit().orElse(null)) {
            case "seconds" -> Duration.ofSeconds(value.getValue().longValue());
            case "milliseconds" -> Duration.ofMillis(value.getValue().longValue());
            case "microseconds" -> Duration.ofNanos(value.getValue().longValue() * 1_000);
            case "nanoseconds" -> Duration.ofNanos(value.getValue().longValue());
            case null, default -> null;
        };
    }

    @Override
    public Duration visit(final SignedValue value) {
        return switch (value.getUnit().orElse(null)) {
            case "seconds" -> Duration.ofSeconds(value.getValue());
            case "milliseconds" -> Duration.ofMillis(value.getValue());
            case "microseconds" -> Duration.ofNanos(value.getValue() * 1_000);
            case "nanoseconds" -> Duration.ofNanos(value.getValue());
            case null, default -> null;
        };
    }
}
