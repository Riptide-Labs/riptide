/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.config.ByteOrderMark;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The inventory file named by {@code riptide.inventory.file}, which is the whole document when
 * discovery is off and supplies only the agent ranges when it is on.
 *
 * <p>The size ceiling and the "not readable" message are the ones {@code InventoryLoader.load} has
 * always applied, moved rather than rewritten, so an operator sees the same sentence they always
 * did.</p>
 */
@Component
public class FileInventoryDocument implements InventoryDocument {

    /**
     * Checked before the read: {@link InventoryLoader#CODE_POINT_LIMIT} bounds the parser, not
     * the read, so bytes, chars, a copy and the object graph are all live before it applies, and
     * a runaway file would OOM first. An Error out of a reload cycle kills the schedule for the
     * process lifetime, so failing on size with the same file-naming message is the safer path.
     * Tied to the parser's own limit rather than a separate number, so the two cannot silently
     * diverge. A 64 MiB inventory is already two orders past anything real.
     */
    static final long MAX_FILE_BYTES = InventoryLoader.CODE_POINT_LIMIT;

    private final InventoryConfig config;

    public FileInventoryDocument(final InventoryConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public String text() {
        final Path file = this.config.getFile();
        if (file == null) {
            return null;
        }
        final long size;
        try {
            size = Files.size(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalStateException(
                    "Inventory file %s is %d bytes, over the %d byte limit: split it or generate less."
                            .formatted(file, size, MAX_FILE_BYTES));
        }
        final String content;
        try {
            content = Files.readString(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        // The boot-time twin of FileWatchTrigger.withoutByteOrderMark (#725). No defect is visible
        // here today: SnakeYAML strips a leading BOM even on the String overload, which is the half
        // of #725 that turned out to be wrong, and boot has no blankness guard for a BOM-only file
        // to slip past the way the reload path's did. This exists so the invariant is "no consumer
        // downstream of a read ever sees U+FEFF" rather than "the parser we happen to use removes
        // it" — a validator added between here and the parse would otherwise inherit the problem.
        return ByteOrderMark.strip(content);
    }

    @Override
    public String name() {
        final Path file = this.config.getFile();
        return file == null ? "riptide.inventory.file (unset)" : file.toString();
    }
}
