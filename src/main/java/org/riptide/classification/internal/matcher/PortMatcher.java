package org.riptide.classification.internal.matcher;

import java.util.Objects;
import java.util.function.Function;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.PortValue;

class PortMatcher implements Matcher {

    private final Function<ClassificationRequest, Integer> valueExtractor;

    private final PortValue value;

    protected PortMatcher(final PortValue ports,
                          final Function<ClassificationRequest, Integer> valueExtractor) {
        this.value = Objects.requireNonNull(ports);
        this.valueExtractor = Objects.requireNonNull(valueExtractor);
    }

    @Override
    public boolean matches(ClassificationRequest request) {
        return this.value.matches(valueExtractor.apply(request));
    }
}
