package org.riptide.classification.internal.matcher;

import java.util.Objects;
import java.util.function.Function;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.IpAddr;
import org.riptide.classification.internal.value.IpValue;

class IpMatcher implements Matcher {

    private final Function<ClassificationRequest, IpAddr> valueExtractor;

    private final IpValue value;

    protected IpMatcher(final IpValue input,
                        final Function<ClassificationRequest, IpAddr> valueExtractor) {
        this.value = Objects.requireNonNull(input);
        this.valueExtractor = Objects.requireNonNull(valueExtractor);
    }

    @Override
    public boolean matches(ClassificationRequest request) {
        return value.isInRange(valueExtractor.apply(request));
    }
}
