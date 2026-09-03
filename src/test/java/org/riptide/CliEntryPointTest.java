/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real process entry point, in a real process.
 *
 * <p>This exists because the same gap survived two rounds of fixing #727 by moving up a level each
 * time. First the routing helper was tested and nothing called it: deleting the call left the suite
 * green. Then the dispatch was tested — but every one of those tests supplies its own two streams,
 * so none of them observes which streams {@code main} actually binds. Rebinding the diagnostic
 * stream to {@code System.out} reintroduces #727 word for word, and stays green, because no
 * in-process test can see the stdout that Logback's fallback appender cached before the test
 * started.</p>
 *
 * <p>A subprocess is the only thing that can see it: its stdout is a file this test opens. That is
 * also why the assertion is on the <em>file</em> rather than on a captured stream — the file is
 * what {@code riptide convert nodes.yaml > new.yaml} produces, and the defect is a log line inside
 * it.</p>
 *
 * <p>No packaging step: surefire's own {@code java.class.path} is a real path list led by
 * {@code target/classes}, so the child runs against exactly what was just compiled. Both streams go
 * to files rather than pipes, because a full pipe buffer would deadlock a child nobody is
 * draining.</p>
 */
class CliEntryPointTest {

    private static final String LEGACY = """
            riptide:
              snmp:
                poll:
                  refresh-interval-ms: 900000
                  snapshot-expiry-ms: 300000
              nodes:
                core-router:
                  subnet-address: 10.20.30.7
                  snmp: {snmp-version: v3, security-name: monitoring}
            """;

    /**
     * The operator's documented invocation, run for real: {@code riptide convert legacy.yaml >
     * new.yaml}. The redirected file must hold the generated documents and no log record, and the
     * diagnostics must reach the other stream.
     */
    @Test
    void theEntryPointKeepsLogRecordsOutOfTheRedirectedDocument(@TempDir final Path dir) throws Exception {
        final Path legacy = Files.writeString(dir.resolve("legacy.yaml"), LEGACY);
        final Path stdout = dir.resolve("new.yaml");
        final Path stderr = dir.resolve("stderr.txt");

        final Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                RiptideApplication.class.getName(),
                "convert", legacy.toString())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        // A bounded wait, so a child that hangs fails this test instead of the whole suite.
        assertThat(child.waitFor(2, TimeUnit.MINUTES))
                .as("the subcommand exits rather than starting the collector")
                .isTrue();

        final String document = Files.readString(stdout, StandardCharsets.UTF_8);
        final String diagnostics = Files.readString(stderr, StandardCharsets.UTF_8);

        assertThat(child.exitValue()).as("stderr was: %s", diagnostics).isZero();
        assertThat(document)
                .as("this file is what the operator commits, so a log record in it is the defect")
                .contains("snapshot-expiry: PT5M")
                .doesNotContain("Converting ")
                .doesNotContain("INFO")
                .doesNotContain("WARN");
        assertThat(diagnostics)
                .as("the record still has to go somewhere the operator can read")
                .contains("Converting ")
                .contains("Converted 1 node(s)");
    }

    /**
     * The generated file is not merely record-free, it is loadable. A stray line that happened to
     * parse as YAML — the #727 report describes one, a message whose {@code ): } made SnakeYAML
     * read it as a second root key — would satisfy a grep and still corrupt the operator's config.
     */
    @Test
    void theRedirectedDocumentIsStillLoadableYaml(@TempDir final Path dir) throws Exception {
        final Path legacy = Files.writeString(dir.resolve("legacy.yaml"), LEGACY);
        final Path stdout = dir.resolve("new.yaml");

        final Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                RiptideApplication.class.getName(),
                "convert", legacy.toString())
                .redirectOutput(stdout.toFile())
                .redirectError(dir.resolve("stderr.txt").toFile())
                .start();
        assertThat(child.waitFor(2, TimeUnit.MINUTES)).isTrue();

        final Iterable<Object> documents = new org.yaml.snakeyaml.Yaml()
                .loadAll(Files.readString(stdout, StandardCharsets.UTF_8));
        assertThat(documents)
                .as("the two documents the converter emits, and nothing that only looks like one")
                .hasSize(2);
    }
}
