package org.riptide.classification.internal.value;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class StringValue {
    private final String input;

    public StringValue(String input) {
        this.input = input;
    }

    public boolean hasWildcard() {
        return input != null && input.contains("*");
    }

    public boolean isWildcard() {
        return "*".equals(input);
    }

    public boolean isNull() {
        return input == null;
    }

    public boolean isEmpty() {
        return input != null && input.isEmpty();
    }

    public boolean isNullOrEmpty() {
        return isNull() || isEmpty();
    }

    public boolean isRanged() {
        if (!isNullOrEmpty()) {
            return input.contains("-");
        }
        return false;
    }

    public String getValue() {
        return input;
    }

    public List<StringValue> splitBy(String separator) {
        return isNullOrEmpty() ? Collections.emptyList() : Arrays.stream(input.split(separator))
                .map(String::trim)
                .filter(segment -> segment.length() > 0)
                .map(StringValue::new)
                .collect(Collectors.toList());
    }

    public boolean contains(final CharSequence charSequence) {
        Objects.requireNonNull(charSequence);
        if (input == null) {
            return false;
        }
        return input.contains(charSequence);
    }
}
