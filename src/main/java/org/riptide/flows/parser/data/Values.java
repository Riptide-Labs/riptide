package org.riptide.flows.parser.data;

import com.google.common.primitives.UnsignedLong;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.BooleanValue;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Values<T> {

    private final Map<String, Value<?>> values;

    private T first;

    private Values(final Map<String, Value<?>> values) {
        this.values = Objects.requireNonNull(values);
        this.first = null;
    }

    public static <T> Values<T> first(final Map<String, Value<?>> values) {
        return new Values<>(values);
    }

    public static <A, B, R> Getter<R> both(final Getter<A> aGetter,
                                           final Getter<B> bGetter,
                                           final BiFunction<A, B, R> combiner) {
        return values -> aGetter.get(values)
                .flatMap(aValue -> bGetter.get(values)
                        .map(bValue -> combiner.apply(aValue, bValue)));
    }

    public Values<T> with(final Getter<T> getter) {
        if (this.first != null) {
            return this;
        }

        getter.get(this.values).ifPresent(value -> {
            this.first = value;
        });

        return this;
    }

    public Values<T> with(final T value) {
        if (this.first != null || value == null) {
            return this;
        }

        this.first = value;

        return this;
    }

    public T getOrNull() {
        return this.first;
    }

    public Optional<T> get() {
        return Optional.ofNullable(this.first);
    }

    @FunctionalInterface
    public interface Getter<T> {
        Optional<T> get(Map<String, Value<?>> values);

        default <R> Getter<R> map(final Function<T, R> mapper) {
            return (values) -> this.get(values).map(mapper);
        }

        default <R> Getter<R> flatMap(final Function<T, Optional<R>> mapper) {
            return values -> this.get(values).flatMap(mapper);
        }

        default <R, O> Getter<R> and(final Getter<O> other, final BiFunction<T, O, R> combiner) {
            return Values.both(this, other, combiner);
        }

        default Getter<T> defaultValue(final Supplier<T> defaultValue) {
            return values -> Optional.ofNullable(this.get(values).orElseGet(defaultValue));
        }

        default Getter<T> defaultValue(final T defaultValue) {
            return values -> Optional.ofNullable(this.get(values).orElse(defaultValue));
        }

        default T getOrNull(final Map<String, Value<?>> values) {
            return this.get(values).orElse(null);
        }
    }

    public static Values.Getter<Long> longValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof UnsignedValue value) {
                return Optional.of(value.getValue().longValue());
            }

            if (raw instanceof SignedValue value) {
                return Optional.of(value.getValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<Integer> intValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof UnsignedValue value) {
                return Optional.of(value.getValue().intValue());
            }

            if (raw instanceof SignedValue value) {
                return Optional.of(value.getValue().intValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<Double> doubleValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof FloatValue value) {
                return Optional.of(value.getValue());
            }

            if (raw instanceof UnsignedValue value) {
                return Optional.of(value.getValue().doubleValue());
            }

            if (raw instanceof SignedValue value) {
                return Optional.of(value.getValue().doubleValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<Double> doubleValue(final String name, final double defaultValue) {
        return doubleValue(name).defaultValue(defaultValue);
    }

    public static Values.Getter<UnsignedLong> unsignedLongValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof UnsignedValue value) {
                return Optional.ofNullable(value.getValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<UnsignedLong> unsignedLongValue(final String name, final UnsignedLong defaultValue) {
        return unsignedLongValue(name).defaultValue(defaultValue);
    }


    public static Values.Getter<Boolean> booleanValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof BooleanValue value) {
                return Optional.ofNullable(value.getValue());
            }

            return Optional.of(Boolean.FALSE);
        };
    }


    public static Values.Getter<String> stringValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof StringValue value) {
                return Optional.ofNullable(value.getValue());
            }

            return Optional.of("");
        };
    }

    public static Values.Getter<InetAddress> inetAddressValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof IPv4AddressValue value) {
                return Optional.ofNullable(value.getValue());
            }

            if (raw instanceof IPv6AddressValue value) {
                return Optional.ofNullable(value.getValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<Instant> timestampValue(final String name) {
        return (values) -> {
            final var raw = values.get(name);

            if (raw instanceof DateTimeValue value) {
                return Optional.ofNullable(value.getValue());
            }

            return Optional.empty();
        };
    }

    public static Values.Getter<Duration> durationValue(final String name, final TemporalUnit unit) {
        return longValue(name)
                .map(amount -> Duration.of(amount, unit));
    }

    public static Values.Getter<Instant> timestampValue(final String name, final TemporalUnit unit) {
        return durationValue(name, unit)
                .map(Instant.EPOCH::plus);
    }
}
