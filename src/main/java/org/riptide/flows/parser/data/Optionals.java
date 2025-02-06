package org.riptide.flows.parser.data;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class Optionals {
    private Optionals() {
    }

    public static <T> Optional<T> of(T a) {
        return Optional.ofNullable(a);
    }

    @SafeVarargs
    public static <T> Optional<T> first(T... values) {
        return Stream.of(values).filter(Objects::nonNull).findFirst();
    }


}
