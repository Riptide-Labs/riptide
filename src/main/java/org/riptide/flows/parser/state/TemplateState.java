package org.riptide.flows.parser.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class TemplateState {
    public final int templateId;

    public final Duration age;

    public TemplateState(final Builder builder) {
        this.templateId = builder.templateId;

        this.age = Objects.requireNonNull(builder.age);
    }

    public static Builder builder(final int templateId) {
        return new Builder(templateId);
    }

    public static class Builder {
        private int templateId;

        private Duration age;

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

        public TemplateState build() {
            return new TemplateState(this);
        }
    }
}
