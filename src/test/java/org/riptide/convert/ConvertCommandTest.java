/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The CLI surface: exit codes, stream separation, and what happens when conversion fails. */
class ConvertCommandTest {

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    private static final String LEGACY = """
            riptide:
              nodes:
                core-router:
                  subnet-address: 10.20.30.7
                  snmp:
                    snmp-version: v3
                    security-name: monitoring
            """;

    @BeforeEach
    void streams() {
        this.out = new ByteArrayOutputStream();
        this.err = new ByteArrayOutputStream();
    }

    private int run(final String... args) {
        return ConvertCommand.run(args,
                new PrintStream(this.out, true, StandardCharsets.UTF_8),
                new PrintStream(this.err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return this.out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return this.err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void matchesOnlyItsOwnSubcommand() {
        assertThat(ConvertCommand.matches("convert")).isTrue();
        assertThat(ConvertCommand.matches("onboard")).isFalse();
        assertThat(ConvertCommand.matches("--help")).isFalse();
    }

    /**
     * The redirect case: {@code riptide convert nodes.yaml > new.yaml} has to produce a file
     * with configuration in it and nothing else, or the operator's first act after upgrading is
     * to hand-edit a summary out of their config.
     */
    @Test
    void configGoesToStdoutAndTheSummaryToStderr() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);

        assertThat(run("convert", input.toString())).isZero();

        assertThat(out()).contains("credentials:").contains("exporters:").contains("---");
        assertThat(out()).doesNotContain("Converted 1 node");
        assertThat(err()).contains("Converted 1 node(s)");
    }

    @Test
    void bothHalvesCanBeWrittenSeparately() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        final Path config = this.tempDir.resolve("out-config.yaml");
        final Path inventory = this.tempDir.resolve("out-inventory.yaml");

        assertThat(run("convert", input.toString(),
                "--out-config", config.toString(), "--out-inventory", inventory.toString())).isZero();

        // each half carries only its own keys, and a header naming where it belongs.
        // Anchored on an indented "version:" line, which only a credential DEFINITION
        // carries. A bare contains("version:") would also match "snmp-version:", the exact
        // spelling this PR fixes, so a regression to it would pass. The
        // inventory half legitimately contains "credentials: credentials-1" as a reference,
        // so a doesNotContain("credentials:") would fail for the wrong reason
        assertThat(Files.readString(config))
                .contains("\n        version:").doesNotContain("exporters:").doesNotContain("agents:");
        assertThat(Files.readString(inventory))
                .contains("exporters:").contains("agents:").doesNotContain("version:");
        assertThat(Files.readString(inventory)).contains("inventory file");
        assertThat(out()).contains("Wrote credential sets").contains("Wrote agent ranges");
    }

    @Test
    void aFailedConversionWritesNothingAndExitsNonZero() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, """
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.7
                      mystery-key: yes
                """);
        final Path config = this.tempDir.resolve("out-config.yaml");

        assertThat(run("convert", input.toString(), "--out-config", config.toString())).isEqualTo(1);

        // a half-converted pair is worse than none: the operator cannot tell which half to trust
        assertThat(Files.exists(config)).isFalse();
        assertThat(err()).contains("mysterykey");
        assertThat(out()).isEmpty();
    }

    @Test
    void anUnreadableInputIsAnErrorNotAStackTrace() {
        assertThat(run("convert", this.tempDir.resolve("absent.yaml").toString())).isEqualTo(1);
        assertThat(err()).contains("cannot read");
        assertThat(out()).isEmpty();
    }

    /**
     * The emitted config is a fragment, and its own header tells the operator it belongs in
     * application.yaml. Pointing --out-config at that file used to truncate it: every other
     * riptide setting gone, exit code 0, and a summary line reporting success.
     */
    @Test
    void anExistingOutputFileIsNotOverwrittenWithoutForce() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        final Path existing = this.tempDir.resolve("application.yaml");
        Files.writeString(existing, "riptide:\n  clickhouse:\n    url: http://ch:8123\n");

        assertThat(run("convert", input.toString(), "--out-config", existing.toString())).isEqualTo(1);
        assertThat(Files.readString(existing)).contains("clickhouse");
        assertThat(err()).contains("already exists").contains("--force");

        assertThat(run("convert", input.toString(), "--out-config", existing.toString(), "--force"))
                .isZero();
        assertThat(Files.readString(existing)).contains("\n        version:");
    }

    /**
     * The both-or-neither invariant, at the point it was actually broken. The config was
     * written first and the inventory second, so an unwritable inventory path left half the
     * pair on disk while the command reported failure.
     */
    @Test
    void anUnwritableSecondPathLeavesNeitherFileBehind() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        final Path config = this.tempDir.resolve("out-config.yaml");
        final Path unwritable = this.tempDir.resolve("no-such-dir").resolve("inv.yaml");

        assertThat(run("convert", input.toString(),
                "--out-config", config.toString(), "--out-inventory", unwritable.toString()))
                .isEqualTo(1);
        assertThat(Files.exists(config)).as("the first half must not survive the failure").isFalse();
    }

    @Test
    void unknownOptionsAreNamedRatherThanTreatedAsTheInputPath() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        assertThat(run("convert", input.toString(), "--out-inventroy", "x.yaml")).isEqualTo(2);
        assertThat(err()).contains("unknown option --out-inventroy");
    }

    @Test
    void helpIsAvailableAndSucceeds() {
        assertThat(run("convert", "--help")).isZero();
        assertThat(out()).contains("usage: riptide convert");
    }

    /** The converter is the only thing that knows the operator still has the retired keys. */
    @Test
    void theSummarySaysToRemoveTheLegacyKeys() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        assertThat(run("convert", input.toString())).isZero();
        assertThat(err()).contains("riptide.nodes").contains("Both fail startup");
    }

    @Test
    void noArgumentsPrintsUsageAndExitsTwo() {
        assertThat(run("convert")).isEqualTo(2);
        assertThat(err()).contains("usage: riptide convert");
    }

    @Test
    void aFlagWithoutItsValueIsRejected() throws Exception {
        final Path input = this.tempDir.resolve("nodes.yaml");
        Files.writeString(input, LEGACY);
        assertThat(run("convert", input.toString(), "--out-config")).isEqualTo(2);
        assertThat(err()).contains("--out-config needs a path");
    }
}
