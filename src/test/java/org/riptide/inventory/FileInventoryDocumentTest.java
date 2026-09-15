/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileInventoryDocumentTest {

    @TempDir
    Path tempDir;

    private static FileInventoryDocument document(final Path file) {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(file);
        return new FileInventoryDocument(config);
    }

    @Test
    void anUnsetFileHasNoText() {
        assertThat(document(null).text()).isNull();
    }

    @Test
    void anUnsetFileStillNamesItselfReadably() {
        assertThat(document(null).name()).contains("riptide.inventory.file");
    }

    @Test
    void aSetFileIsReadAndNamedByItsPath() throws IOException {
        final Path file = this.tempDir.resolve("inventory.yaml");
        Files.writeString(file, "riptide:\n  exporters: {}\n");

        assertThat(document(file).text()).contains("exporters");
        assertThat(document(file).name()).isEqualTo(file.toString());
    }

    @Test
    void aSetButMissingFileFailsNamingThePath() {
        final Path missing = this.tempDir.resolve("missing.yaml");

        assertThatThrownBy(() -> document(missing).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable")
                .hasMessageContaining("missing.yaml");
    }

    @Test
    void aByteOrderMarkOnTheFrontIsRemoved() throws IOException {
        final Path file = this.tempDir.resolve("bom.yaml");
        Files.write(file, "﻿riptide:\n  exporters: {}\n".getBytes(StandardCharsets.UTF_8));

        assertThat(document(file).text()).startsWith("riptide:");
    }

    /** Distinct from an unset file: a configured, present, zero-byte file is a real document. */
    @Test
    void anEmptyFileReadsAsEmptyTextNotNull() throws IOException {
        final Path file = this.tempDir.resolve("empty.yaml");
        Files.writeString(file, "");

        assertThat(document(file).text()).isNotNull().isEmpty();
    }

    /**
     * The exact operator-facing sentence, restored verbatim from the {@code InventoryLoader.load}
     * this class replaced: the byte count and the "split it or generate less" remediation. A
     * sparse file (set-length, not written) keeps this fast at the real ceiling rather than
     * approximating it with a smaller ad hoc limit.
     */
    @Test
    void aFileOverTheSizeCeilingFailsNamingTheSizeAndTheLimit() throws IOException {
        final Path file = this.tempDir.resolve("huge.yaml");
        final long oversize = FileInventoryDocument.MAX_FILE_BYTES + 1;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(oversize);
        }

        assertThatThrownBy(() -> document(file).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Inventory file %s is %d bytes, over the %d byte limit: split it or generate less."
                        .formatted(file, oversize, FileInventoryDocument.MAX_FILE_BYTES));
    }
}
