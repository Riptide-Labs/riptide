/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Resolves {@code file:///path} (whole file, trimmed) or {@code file:///path#key}
 * (a key inside a properties file).
 *
 * <p>When {@code riptide.secrets.allowed-paths} is set, only files below one of the listed
 * directories are readable — the same sandboxing idea as Kafka's {@code allowed.paths}.</p>
 */
@Component
public class FileSecretResolver implements SecretResolver {

    private final List<Path> allowedPaths;

    public FileSecretResolver(@Value("${riptide.secrets.allowed-paths:}") final List<String> allowedPaths) {
        this.allowedPaths = allowedPaths.stream()
                .filter(p -> !p.isBlank())
                .map(p -> {
                    try {
                        // match the toRealPath form used when checking refs
                        return Path.of(p).toRealPath();
                    } catch (IOException e) {
                        return Path.of(p).toAbsolutePath().normalize();
                    }
                })
                .toList();
    }

    @Override
    public String scheme() {
        return "file";
    }

    @Override
    public String resolve(final SecretRef ref) {
        final Path path;
        final String content;
        try {
            // toRealPath resolves symlinks so they cannot escape the allowed-paths sandbox
            path = Path.of(ref.getValue()).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read secret ref " + ref, e);
        }

        if (!this.allowedPaths.isEmpty() && this.allowedPaths.stream().noneMatch(path::startsWith)) {
            throw new IllegalArgumentException("Path of secret ref " + ref + " is outside riptide.secrets.allowed-paths");
        }

        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read secret ref " + ref, e);
        }

        if (ref.getKey() == null) {
            return content.trim();
        }

        final Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse properties for secret ref " + ref, e);
        }
        final String value = properties.getProperty(ref.getKey());
        if (value == null) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' not found for secret ref " + ref);
        }
        return value;
    }
}
