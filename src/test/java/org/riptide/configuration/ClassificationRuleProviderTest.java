/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.config.ClassificationConfig;
import org.springframework.core.io.FileSystemResource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClassificationRuleProviderTest {

    private static final String HEADER = "name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional\n";

    @TempDir
    Path tempDir;

    @Test
    void verifyReloadPicksUpFileChanges() throws Exception {
        final var rulesFile = tempDir.resolve("rules.csv");
        Files.writeString(rulesFile, HEADER + "ntp;udp;;;;123;;true\n");

        final var config = new ClassificationConfig();
        config.setRules(new FileSystemResource(rulesFile));

        final var configuration = new RiptideConfiguration();
        final var provider = configuration.classificationRuleProvider(
                new CsvImporter(), configuration.classificationRulesSource(config));
        Assertions.assertThat(provider.getRules()).hasSize(1);

        Files.writeString(rulesFile, HEADER + "ntp;udp;;;;123;;true\nssh;tcp;;;;22;;true\n");
        Assertions.assertThat(provider.getRules()).hasSize(2);
    }

    @Test
    void verifyFailsFastOnUnreadableResource() {
        final var config = new ClassificationConfig();
        config.setRules(new FileSystemResource(tempDir.resolve("missing.csv")));

        final var configuration = new RiptideConfiguration();
        Assertions.assertThatThrownBy(() -> configuration.classificationRuleProvider(
                        new CsvImporter(), configuration.classificationRulesSource(config)))
                .isInstanceOf(UncheckedIOException.class);
    }
}
