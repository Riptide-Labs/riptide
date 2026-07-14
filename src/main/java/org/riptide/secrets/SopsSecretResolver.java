/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves {@code sops://<file>#<key>} by decrypting the file with the
 * <a href="https://getsops.io">sops</a> binary and looking up a (dot-separated, nested)
 * key in the decrypted YAML/JSON document. Without a {@code #key}, the whole decrypted
 * content (trimmed) is the secret.
 *
 * <p>Decrypted files are cached in memory for the lifetime of the process — SOPS is a
 * boot-time secret source; restart (or touch a config reload, once available) to pick up
 * re-encrypted values.</p>
 *
 * <p>Configuration: {@code riptide.secrets.sops.command} (default {@code sops}) and
 * {@code riptide.secrets.sops.age-key-file} (sets {@code SOPS_AGE_KEY_FILE} for the
 * subprocess).</p>
 */
@Component
public class SopsSecretResolver implements SecretResolver {

    private static final long DECRYPT_TIMEOUT_SECONDS = 30;

    private final List<String> command;
    private final String ageKeyFile;
    private final Map<Path, DecryptedFile> cache = new ConcurrentHashMap<>();

    /** Config hot-reload hook: rotated secrets must decrypt fresh at the next resolve. */
    public void invalidateCache() {
        this.cache.clear();
    }

    public SopsSecretResolver(@Value("${riptide.secrets.sops.command:sops}") final String command,
                              @Value("${riptide.secrets.sops.age-key-file:}") final String ageKeyFile) {
        this.command = List.of(command.trim().split("\\s+"));
        this.ageKeyFile = ageKeyFile;
    }

    private record DecryptedFile(String raw, Map<String, String> values) {
    }

    @Override
    public String scheme() {
        return "sops";
    }

    @Override
    public String resolve(final SecretRef ref) {
        final Path path;
        try {
            path = Path.of(ref.getValue()).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read secret ref " + ref, e);
        }

        final DecryptedFile decrypted = this.cache.computeIfAbsent(path, p -> decrypt(p, ref));

        if (ref.getKey() == null) {
            return decrypted.raw().trim();
        }
        final String value = decrypted.values().get(ref.getKey());
        if (value == null) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' not found for secret ref " + ref);
        }
        return value;
    }

    private DecryptedFile decrypt(final Path path, final SecretRef ref) {
        final List<String> commandLine = new ArrayList<>(this.command);
        commandLine.add("-d");
        commandLine.add(path.toString());

        final ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        if (!this.ageKeyFile.isBlank()) {
            processBuilder.environment().put("SOPS_AGE_KEY_FILE", this.ageKeyFile);
        }

        final String stdout;
        try {
            final Process process = processBuilder.start();
            // no interactive prompts (e.g. for a missing key) — fail instead
            process.getOutputStream().close();
            if (!process.waitFor(DECRYPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Decrypting secret ref " + ref + " timed out after " + DECRYPT_TIMEOUT_SECONDS + "s");
            }
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalArgumentException("Decrypting secret ref " + ref + " failed (exit " + process.exitValue() + "): " + stderr.trim());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot run '" + String.join(" ", this.command) + "' for secret ref " + ref, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Interrupted while decrypting secret ref " + ref, e);
        }

        return new DecryptedFile(stdout, flatten(stdout));
    }

    private static Map<String, String> flatten(final String document) {
        final Object parsed;
        try {
            parsed = new Yaml().load(document);
        } catch (RuntimeException e) {
            // not a YAML/JSON document (e.g. sops binary format) — only whole-content refs work
            return Map.of();
        }
        final Map<String, String> flat = new ConcurrentHashMap<>();
        if (parsed instanceof Map<?, ?> map) {
            flatten("", map, flat);
        }
        return flat;
    }

    private static void flatten(final String prefix, final Map<?, ?> map, final Map<String, String> into) {
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key, nested, into);
            } else if (entry.getValue() != null) {
                into.put(key, String.valueOf(entry.getValue()));
            }
        }
    }
}
