package org.riptide.flows.parser.state;

import java.util.List;

import com.google.common.collect.ImmutableList;

public class ParserState {

    public final List<ExporterState> exporters;

    private ParserState(final Builder builder) {
        this.exporters = builder.exporters.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ImmutableList.Builder<ExporterState> exporters = ImmutableList.builder();

        private Builder() {
        }

        public Builder withExporter(final ExporterState state) {
            this.exporters.add(state);
            return this;
        }

        public Builder withExporter(final ExporterState.Builder state) {
            return this.withExporter(state.build());
        }

        public ParserState build() {
            return new ParserState(this);
        }
    }
}
