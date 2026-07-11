/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SecretResolversTest {

    @Test
    public void resolvesPlainRefs() {
        assertThat(SecretResolvers.defaults().resolve(SecretRef.of("c0mmunity"))).isEqualTo("c0mmunity");
    }

    @Test
    public void nullRefResolvesToNull() {
        assertThat(SecretResolvers.defaults().resolve(null)).isNull();
    }

    @Test
    public void unknownSchemeThrows() {
        assertThatThrownBy(() -> SecretResolvers.defaults().resolve(SecretRef.of("vault://secret/x#y")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vault");
    }

    @Test
    public void resolvesEnvRefs() {
        final Map<String, String> env = Map.of("RIPTIDE_SNMP_COMMUNITY", "fr0m-env");
        final SecretResolvers resolvers = new SecretResolvers(List.of(new EnvSecretResolver(env::get)));

        assertThat(resolvers.resolve(SecretRef.of("env://RIPTIDE_SNMP_COMMUNITY"))).isEqualTo("fr0m-env");
        assertThatThrownBy(() -> resolvers.resolve(SecretRef.of("env://MISSING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    public void resolvesFileRefs(@TempDir Path tempDir) throws Exception {
        final Path secretFile = tempDir.resolve("community");
        Files.writeString(secretFile, "fr0m-file\n");

        final SecretResolvers resolvers = new SecretResolvers(List.of(new FileSecretResolver(List.of())));
        assertThat(resolvers.resolve(SecretRef.of("file://" + secretFile))).isEqualTo("fr0m-file");
    }

    @Test
    public void resolvesFileRefsWithPropertiesKey(@TempDir Path tempDir) throws Exception {
        final Path secretFile = tempDir.resolve("secrets.properties");
        Files.writeString(secretFile, "snmp.community=fr0m-props\nother=x\n");

        final SecretResolvers resolvers = new SecretResolvers(List.of(new FileSecretResolver(List.of())));
        assertThat(resolvers.resolve(SecretRef.of("file://" + secretFile + "#snmp.community"))).isEqualTo("fr0m-props");
        assertThatThrownBy(() -> resolvers.resolve(SecretRef.of("file://" + secretFile + "#missing.key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.key");
    }

    @Test
    public void fileResolverEnforcesAllowedPaths(@TempDir Path tempDir) throws Exception {
        final Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
        final Path outside = Files.createDirectory(tempDir.resolve("outside"));
        final Path allowedSecret = allowed.resolve("s");
        final Path outsideSecret = outside.resolve("s");
        Files.writeString(allowedSecret, "ok");
        Files.writeString(outsideSecret, "nope");

        final SecretResolvers resolvers = new SecretResolvers(List.of(new FileSecretResolver(List.of(allowed.toString()))));
        assertThat(resolvers.resolve(SecretRef.of("file://" + allowedSecret))).isEqualTo("ok");
        assertThatThrownBy(() -> resolvers.resolve(SecretRef.of("file://" + outsideSecret)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-paths");
    }

    @Test
    public void symlinksCannotEscapeAllowedPaths(@TempDir Path tempDir) throws Exception {
        final Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
        final Path outside = Files.createDirectory(tempDir.resolve("outside"));
        final Path target = outside.resolve("s");
        Files.writeString(target, "nope");
        final Path escape = allowed.resolve("escape");
        Files.createSymbolicLink(escape, target);

        final SecretResolvers resolvers = new SecretResolvers(List.of(new FileSecretResolver(List.of(allowed.toString()))));
        assertThatThrownBy(() -> resolvers.resolve(SecretRef.of("file://" + escape)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-paths");
    }

    @Test
    public void unreadableFileThrows() {
        final SecretResolvers resolvers = new SecretResolvers(List.of(new FileSecretResolver(List.of())));
        assertThatThrownBy(() -> resolvers.resolve(SecretRef.of("file:///does/not/exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
