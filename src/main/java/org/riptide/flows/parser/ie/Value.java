package org.riptide.flows.parser.ie;

import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.util.Objects;
import java.util.Optional;

public abstract class Value<T> {

    private final String name;
    private final Semantics semantics;
    private final String unit;

    protected Value(final String name,
                    final Semantics semantics,
                    final String unit) {
        this.name = Objects.requireNonNull(name);
        this.semantics = semantics;
        this.unit = unit;
    }

    public String getName() {
        return this.name;
    }

    public Optional<Semantics> getSemantics() {
        return Optional.ofNullable(this.semantics);
    }

    public Optional<String> getUnit() { return Optional.ofNullable(this.unit); }

    public abstract T getValue();

    public abstract <X> X accept(ValueVisitor<X> visitor);

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
}
