package org.riptide.flows.parser.transport;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.*;

public interface MessageUtils {
    static Long longValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof UnsignedValue value) {
            return value.getValue().longValue();
        }

        if (values.get(name) instanceof SignedValue value) {
            return value.getValue();
        }

        return null;
    }

    static Integer intValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof UnsignedValue value) {
            return value.getValue().intValue();
        }

        if (values.get(name) instanceof SignedValue value) {
            return value.getValue().intValue();
        }

        return null;
    }

    static Double doubleValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof UnsignedValue value) {
            return value.getValue().doubleValue();
        }

        if (values.get(name) instanceof SignedValue value) {
            return value.getValue().doubleValue();
        }

        return null;
    }

    static double doubleValue(final Map<String, Value<?>> values, final String name, final double defaultValue) {
        return switch (doubleValue(values, name)) {
            case Double value -> value;
            case null -> defaultValue;
        };
    }

    static UnsignedLong unsignedLongValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof UnsignedValue value) {
            return value.getValue();
        }

        return null;
    }

    static UnsignedLong unsignedLongValue(final Map<String, Value<?>> values, final String name, final UnsignedLong defaultValue) {
        return switch (unsignedLongValue(values, name)) {
            case UnsignedLong value -> value;
            case null -> defaultValue;
        };
    }


    static Boolean booleanValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof BooleanValue value) {
            return value.getValue();
        }

        return Boolean.FALSE;
    }


    static String stringValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof StringValue value) {
            return value.getValue();
        }
        return "";
    }

    static InetAddress inetAddressValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof IPv4AddressValue value) {
            return value.getValue();
        }
        if (values.get(name) instanceof IPv6AddressValue value) {
            return value.getValue();
        }
        return null;
    }

    static Instant timeValue(final Map<String, Value<?>> values, final String name) {
        if (values.get(name) instanceof DateTimeValue value) {
            return value.getValue();
        }
        return null;
    }

    static Instant timestampValue(final Map<String, Value<?>> values, final String name, final TemporalUnit unit) {
        final var amount = longValue(values, name);
        if (amount == null) {
            return null;
        }

        return Instant.EPOCH.plus(Duration.of(amount, unit));
    }

    static Duration durationValue(final Map<String, Value<?>> values, final String name, final TemporalUnit unit) {
        final var amount = longValue(values, name);
        if (amount == null) {
            return null;
        }

        return Duration.of(amount, unit);
    }

    @SafeVarargs
    static <V> V first(final V... values) {
        return Stream.of(values)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @SafeVarargs
    static <V> Optional<V> first(final Optional<V>... values) {
        return Stream.of(values)
                .filter(Objects::nonNull)
                .flatMap(Optional::stream)
                .findFirst();
    }
}
