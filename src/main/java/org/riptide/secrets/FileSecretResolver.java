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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves {@code file:///path} (whole file, trimmed) or {@code file:///path#key}
 * (a key inside a properties file).
 *
 * <p>A key the file declares more than once is refused rather than collapsed to the last
 * declaration — see {@link DeclarationCounting}.</p>
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

        final DeclarationCounting properties = new DeclarationCounting();
        try {
            properties.load(new StringReader(content));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse properties for secret ref " + ref, e);
        }

        final int declared = properties.declarations.getOrDefault(ref.getKey(), 0);
        if (declared > 1) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' is declared " + declared
                    + " times for secret ref " + ref + " — riptide will not guess which is meant."
                    + " Keep one, or put this secret in its own file.");
        }

        final String value = properties.getProperty(ref.getKey());
        if (value == null) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' not found for secret ref " + ref);
        }
        return value;
    }

    /**
     * Counts how many times the file declares each key, so a repeated key can be refused rather
     * than collapsed to the last one (#577).
     *
     * <p>{@link Properties#load} calls {@link #put} once per declaration and keeps the last, saying
     * nothing. Counting the calls is therefore an exact answer to "would this key be collapsed",
     * because it is the real parser doing the lexing.</p>
     *
     * <p><b>Asking the parser rather than imitating it is the whole point.</b> An earlier version
     * counted declarations with a regex over the raw text and disagreed with {@code Properties} in
     * three ways: it missed the whitespace separator that {@code Properties} accepts alongside
     * {@code =} and {@code :}, so a {@code community public} file kept the defect; it counted a
     * folded line continuation as a second declaration, refusing a file that resolves
     * unambiguously; and it could not see a key whose separator is escaped ({@code snmp\.community}).
     * Every one of those is a gap between an approximation and the thing it approximates.</p>
     *
     * <p>This also means the check needs no format of its own. A nested YAML file is still counted
     * correctly — {@code Properties} reads it by stripping the indentation, which is exactly why
     * two secrets under different parents collide on the same bare key — and a file no YAML parser
     * would accept, such as one indented with tabs, is counted just the same.</p>
     */
    private static final class DeclarationCounting extends Properties {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final Map<String, Integer> declarations = new HashMap<>();

        @Override
        public synchronized Object put(final Object key, final Object value) {
            this.declarations.merge((String) key, 1, Integer::sum);
            return super.put(key, value);
        }

        // A short-lived parsing accumulator, never compared or stored. Hashtable's value-based
        // equality would ignore the count this class adds, so identity is the honest contract —
        // and SpotBugs requires the pair to be stated rather than inherited.
        @Override
        public boolean equals(final Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

}
