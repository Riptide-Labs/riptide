/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the resolver against fake decrypt commands so they run without a sops binary;
 * the subprocess handling, parsing, key lookup, and caching are what is under test.
 */
@DisabledOnOs(OS.WINDOWS)
public class SopsSecretResolverTest {

    private static final String YAML = """
            snmp:
              community: fr0m-sops
              auth-passphrase: s3cret
            top: level
            """;

    private Path fakeSops(final Path dir, final String script) throws IOException {
        final Path command = dir.resolve("fake-sops.sh");
        Files.writeString(command, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwxr-xr-x"));
        return command;
    }

    private Path secretsFile(final Path dir) throws IOException {
        // content is irrelevant — the fake command produces the "decrypted" output
        final Path file = dir.resolve("secrets.sops.yaml");
        Files.writeString(file, "encrypted");
        return file;
    }

    @Test
    public void resolvesNestedAndTopLevelKeys(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "cat <<'EOF'\n" + YAML + "EOF\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        assertThat(resolver.resolve(SecretRef.of("sops://" + file + "#snmp.community"))).isEqualTo("fr0m-sops");
        assertThat(resolver.resolve(SecretRef.of("sops://" + file + "#snmp.auth-passphrase"))).isEqualTo("s3cret");
        assertThat(resolver.resolve(SecretRef.of("sops://" + file + "#top"))).isEqualTo("level");
    }

    @Test
    public void missingKeyThrows(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "cat <<'EOF'\n" + YAML + "EOF\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("sops://" + file + "#missing.key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.key");
    }

    @Test
    public void withoutKeyReturnsWholeContent(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "printf 'raw-secret\\n'\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        assertThat(resolver.resolve(SecretRef.of("sops://" + file))).isEqualTo("raw-secret");
    }

    @Test
    public void decryptionRunsOncePerFile(@TempDir Path dir) throws Exception {
        final Path counter = dir.resolve("invocations");
        final Path command = fakeSops(dir, "echo x >> " + counter + "\ncat <<'EOF'\n" + YAML + "EOF\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        resolver.resolve(SecretRef.of("sops://" + file + "#snmp.community"));
        resolver.resolve(SecretRef.of("sops://" + file + "#top"));

        assertThat(Files.readAllLines(counter)).hasSize(1);
    }

    @Test
    public void failingCommandThrowsWithStderr(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "echo 'no key found' >&2\nexit 1\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("sops://" + file + "#snmp.community")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no key found");
    }

    @Test
    public void missingCommandThrows(@TempDir Path dir) throws Exception {
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(dir.resolve("does-not-exist").toString(), "");

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("sops://" + file + "#snmp.community")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void missingFileThrows(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "exit 0\n");
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "");

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("sops://" + dir.resolve("nope.yaml") + "#k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void ageKeyFileIsPassedToSubprocess(@TempDir Path dir) throws Exception {
        final Path command = fakeSops(dir, "printf 'key-file: %s\\n' \"$SOPS_AGE_KEY_FILE\"\n");
        final Path file = secretsFile(dir);
        final SopsSecretResolver resolver = new SopsSecretResolver(command.toString(), "/etc/riptide/age.key");

        assertThat(resolver.resolve(SecretRef.of("sops://" + file + "#key-file"))).isEqualTo("/etc/riptide/age.key");
    }
}
