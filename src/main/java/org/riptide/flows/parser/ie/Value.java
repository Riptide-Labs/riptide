package org.riptide.flows.parser.ie;

import org.riptide.flows.parser.ie.values.BooleanValue;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.riptide.flows.parser.ie.values.ListValue;
import org.riptide.flows.parser.ie.values.MacAddressValue;
import org.riptide.flows.parser.ie.values.NullValue;
import org.riptide.flows.parser.ie.values.OctetArrayValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UndeclaredValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import java.util.Objects;
import java.util.Optional;

public abstract class Value<T> {

    public interface Visitor {
        default void accept(final NullValue value) { }

        default void accept(final BooleanValue value) { }

        default void accept(final DateTimeValue value) { }

        default void accept(final FloatValue value) { }

        default void accept(final IPv4AddressValue value) { }

        default void accept(final IPv6AddressValue value) { }

        default void accept(final MacAddressValue value) { }

        default void accept(final OctetArrayValue value) { }

        default void accept(final SignedValue value) { }

        default void accept(final StringValue value) { }

        default void accept(final UnsignedValue value) { }

        default void accept(final ListValue value) { }

        default void accept(final UndeclaredValue value) { }

    }

    private final String name;

    private final Semantics semantics;

    protected Value(final String name,
                    final Semantics semantics) {
        this.name = Objects.requireNonNull(name);
        this.semantics = semantics;
    }

    public String getName() {
        return this.name;
    }

    public Optional<Semantics> getSemantics() {
        return Optional.ofNullable(this.semantics);
    }

    public abstract T getValue();

    public abstract void visit(Visitor visitor);

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof Value<?> value)) return false;
        return Objects.equals(this.name, value.name)
                && Objects.equals(this.getValue(), value.getValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.name, this.getValue());
    }

    public abstract Typed typed();

    public interface Typed {
        default Optional<BooleanValue> asBooleanValue() { return Optional.empty(); }
        default Optional<DateTimeValue> asDateTimeValue() { return Optional.empty(); }
        default Optional<FloatValue> asFloatValue() { return Optional.empty(); }
        default Optional<IPv4AddressValue> asIPv4AddressValue() { return Optional.empty(); }
        default Optional<IPv6AddressValue> asIPv6AddressValue() { return Optional.empty(); }
        default Optional<ListValue> asListValue() { return Optional.empty(); }
        default Optional<MacAddressValue> asMacAddressValue() { return Optional.empty(); }
        default Optional<NullValue> asNullValue() { return Optional.empty(); }
        default Optional<OctetArrayValue> asOctetArrayValue() { return Optional.empty(); }
        default Optional<SignedValue> asSignedValue() { return Optional.empty(); }
        default Optional<StringValue> asStringValue() { return Optional.empty(); }
        default Optional<UndeclaredValue> asUndeclaredValue() { return Optional.empty(); }
        default Optional<UnsignedValue> asUnsignedValue() { return Optional.empty(); }
    }
}
