package org.riptide.flows.parser.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.ie.Value;

import com.google.common.collect.ImmutableList;

public class OptionState {
    public final int templateId;

    public final Duration age;

    public final List<NamedValue> selectors;
    public final List<NamedValue> values;

    public OptionState(Builder builder) {
        this.templateId = builder.templateId;

        this.age = builder.age;

        this.selectors = Objects.requireNonNull(builder.selectors.build());
        this.values = Objects.requireNonNull(builder.values.build());
    }

    public static Builder builder(final int templateId) {
        return new Builder(templateId);
    }

    public static final class Builder {
        private int templateId;

        private Duration age;

        private final ImmutableList.Builder<NamedValue> selectors = ImmutableList.builder();
        private final ImmutableList.Builder<NamedValue> values = ImmutableList.builder();

        private Builder(final int templateId) {
            this.templateId = templateId;
        }

        public Builder withTemplateId(final int templateId) {
            this.templateId = templateId;
            return this;
        }

        public Builder withAge(final Duration age) {
            this.age = age;
            return this;
        }

        public Builder withInsertionTime(final Instant insertionTime) {
            return this.withAge(Duration.between(insertionTime, Instant.now()));
        }

        public Builder withSelectors(final Iterable<Value<?>> selectors) {
            for (final Value<?> selector : selectors) {
                this.selectors.add(NamedValue.from(selector));
            }
            return this;
        }

        public Builder withSelector(final Value<?> selector) {
            this.selectors.add(NamedValue.from(selector));
            return this;
        }

        public Builder withValues(final Iterable<Value<?>> values) {
            for (final Value<?> value : values) {
                this.values.add(NamedValue.from(value));
            }
            return this;
        }

        public Builder withValue(final Value<?> value) {
            this.values.add(NamedValue.from(value));
            return this;
        }

        public OptionState build() {
            return new OptionState(this);
        }
    }

    public static class NamedValue {
        public final String name;
        public final String value;

        public NamedValue(final String name, final String value) {
            this.name = Objects.requireNonNull(name);
            this.value = Objects.requireNonNull(value);
        }

        public static NamedValue from(final Value<?> value) {
            return new NamedValue(value.getName(), value.getValue().toString());
        }
    }
}
