package org.riptide.flows.parser.state;

import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;

public class ExporterState {
    public final String key;

    public final List<TemplateState> templates;
    public final List<OptionState> options;

    public ExporterState(Builder builder) {
        this.key = Objects.requireNonNull(builder.key);

        this.templates = Objects.requireNonNull(builder.templates.build());
        this.options = Objects.requireNonNull(builder.options.build());
    }

    public static Builder builder(final String sessionKey) {
        return new Builder(sessionKey);
    }

    public static final class Builder {
        private final String key;

        private final ImmutableList.Builder<TemplateState> templates = ImmutableList.builder();
        private final ImmutableList.Builder<OptionState> options = ImmutableList.builder();

        private Builder(final String key) {
            this.key = Objects.requireNonNull(key);
        }

        public Builder withTemplate(final TemplateState state) {
            this.templates.add(state);
            return this;
        }

        public Builder withTemplate(final TemplateState.Builder state) {
            return this.withTemplate(state.build());
        }

        public Builder withOptions(final OptionState state) {
            this.options.add(state);
            return this;
        }

        public Builder withOptions(final OptionState.Builder state) {
            return this.withOptions(state.build());
        }

        public ExporterState build() {
            return new ExporterState(this);
        }
    }
}
