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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves {@code file:///path} (whole file, trimmed) or {@code file:///path#key}
 * (a key inside a properties file).
 *
 * <p>A key the file declares more than once is refused rather than collapsed to the last
 * declaration — see {@link #refuseIfDeclaredTwice}.</p>
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

        refuseIfDeclaredTwice(content, ref);

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

    /**
     * Refuse a key the file declares more than once, naming the lines (#577).
     *
     * <p>{@link Properties} collapses a repeated key to the last one and says nothing. In a flat
     * file that is a duplicated line; in a nested one — which {@code Properties} reads by stripping
     * the indentation — it is two different secrets under two different parents:</p>
     *
     * <pre>
     * snmp:
     *   core:
     *     community: core-secret     # &lt;- #community resolved here...
     *   edge:
     *     community: edge-secret     # &lt;- ...until this site was added, silently
     * </pre>
     *
     * <p>The reference is correct when written and stays correct for as long as the file declares
     * the key once. What breaks it is an unrelated later edit, and riptide resolves secrets per
     * SNMP walk, so the wrong value goes out on the next poll with no restart to notice at.</p>
     *
     * <p>The pattern anchors at the start of the line, which is also what excludes a commented-out
     * declaration ({@code # community=old}) and a longer key that merely ends with this one
     * ({@code old_community}) — neither can reach the key through leading whitespace alone. An
     * explicit comment check was tried and removed as unreachable for that reason.</p>
     *
     * <p><b>Counted in the raw text, deliberately, rather than parsed.</b> Parsing would make the
     * check depend on the file being valid in some format, and the fallback for "did not parse" is
     * exactly the collapse being guarded against — a nested document with a tab in it would sail
     * straight through. Counting declarations needs no format at all, so a file riptide cannot
     * interpret still cannot resolve ambiguously.</p>
     */
    private static void refuseIfDeclaredTwice(final String content, final SecretRef ref) {
        final Pattern declaration = Pattern.compile("^\\s*" + Pattern.quote(ref.getKey()) + "\\s*[:=]");
        final List<Integer> lines = new ArrayList<>();
        final String[] all = content.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            if (declaration.matcher(all[i]).find()) {
                lines.add(i + 1);
            }
        }
        if (lines.size() > 1) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' is declared " + lines.size()
                    + " times for secret ref " + ref + " (lines " + lines.stream().map(String::valueOf)
                    .collect(Collectors.joining(", ")) + ") — riptide will not guess which is meant."
                    + " Keep one, or put this secret in its own file.");
        }
    }
}
