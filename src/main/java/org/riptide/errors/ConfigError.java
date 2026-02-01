package org.riptide.errors;

public class ConfigError extends RuntimeException {
    public ConfigError(final String message) {
        super(message);
    }
}
