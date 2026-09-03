/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import java.util.Arrays;

/**
 * Removes a leading UTF-8 byte-order mark from an operator-authored file, at the point it is read
 * (#725).
 *
 * <p>One class rather than a copy per reader, because there are four read paths and they are in
 * four packages: the watched-file source both config reloaders share, the classification ruleset's
 * source, and the inventory loader's boot-time read. A private helper per caller is the shape
 * {@link FileWatchTrigger}'s own history warns about — two copies of one rule drifted until a
 * whitespace-only file was a benign skip for one reloader and a counted failure for the other
 * (#561).</p>
 *
 * <p><b>Strip on read, not at the parser.</b> Every parser this project hands bytes to happens to
 * cope with a BOM differently: SnakeYAML strips one even on its {@code String} overload, while
 * commons-csv does not, so a BOM'd rules file fails its header comparison with a message in which
 * the expected and actual headers render identically. Removing it once, where the bytes enter,
 * means no downstream reader has to know which of those it is talking to.</p>
 *
 * <p><b>Exactly one is removed.</b> A doubled BOM is not a thing an editor produces; it comes from
 * concatenating two already-BOM'd files, which corrupts the document in ways this cannot repair
 * anyway. Removing one keeps the rule stated in a sentence, and a second one still reaches the
 * blank check, where a file that is nothing but BOMs is refused rather than committed.</p>
 *
 * <p><b>Only the UTF-8 form.</b> UTF-16 and UTF-32 marks are deliberately left, because they are
 * not a prefix on an otherwise-UTF-8 file: they mean the whole file is in another encoding, and
 * quietly dropping two bytes would turn a file the strict decoder rejects with "is not valid UTF-8"
 * into one that fails somewhere less honest.</p>
 */
public final class ByteOrderMark {

    /** The UTF-8 encoding of U+FEFF, which is what a Windows editor puts on the front of a file. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ByteOrderMark() {
    }

    /** The content without a leading UTF-8 BOM; the same array when there is none to remove. */
    public static byte[] strip(final byte[] content) {
        if (content.length < UTF8_BOM.length
                || !Arrays.equals(content, 0, UTF8_BOM.length, UTF8_BOM, 0, UTF8_BOM.length)) {
            return content;
        }
        return Arrays.copyOfRange(content, UTF8_BOM.length, content.length);
    }

    /** As {@link #strip(byte[])}, for a path that has already decoded. */
    public static String strip(final String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }
}
