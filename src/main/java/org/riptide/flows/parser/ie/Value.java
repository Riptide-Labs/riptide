package org.riptide.flows.parser.ie;

import java.util.Objects;
import java.util.Optional;

public abstract class Value<T> {

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
