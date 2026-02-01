package org.riptide.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public class ConfigFailureAnalyzer extends AbstractFailureAnalyzer<ConfigError> {

    @Override
    public @Nullable FailureAnalysis analyze(Throwable failure) {
        return super.analyze(failure);
    }

    @Override
    protected @Nullable FailureAnalysis analyze(final Throwable rootFailure, final ConfigError cause) {
        final var message = "Configuration Error: " + cause.getMessage();
        final var action = "Please check your configuration file";
        return new FailureAnalysis(message, action, cause);
    }
}
