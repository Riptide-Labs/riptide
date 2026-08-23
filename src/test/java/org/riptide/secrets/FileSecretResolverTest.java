/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code file://} secret resolution (#577).
 *
 * <p>This class did not exist before the defect it covers. The resolver with the silent failure
 * mode was the only one in the package without a test class of its own, which is not a coincidence
 * worth preserving.</p>
 */
class FileSecretResolverTest {

    private static final FileSecretResolver RESOLVER = new FileSecretResolver(List.of());

    private static String resolve(final Path file, final String key) {
        return RESOLVER.resolve(SecretRef.of("file://" + file + (key == null ? "" : "#" + key)));
    }

    private static Path write(final Path dir, final String name, final String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    /**
     * The defect, as it actually reaches an operator.
     *
     * <p>It is not a mistake made when the reference is written — the reference is correct, and
     * stays correct, until an unrelated edit adds a second declaration elsewhere in the file.
     * Asserting only the two-declaration half would also pass against an implementation that
     * refused every key, which would break every configuration.</p>
     */
    @Test
    void aKeyStopsResolvingWhenASecondDeclarationAppears(@TempDir Path dir) throws IOException {
        final Path oneSite = write(dir, "one.yaml", """
                snmp:
                  core:
                    community: core-secret
                """);

        assertThat(resolve(oneSite, "community"))
                .as("one declaration is unambiguous and must keep resolving")
                .isEqualTo("core-secret");

        final Path twoSites = write(dir, "two.yaml", """
                snmp:
                  core:
                    community: core-secret
                  edge:
                    community: edge-secret
                """);

        assertThatThrownBy(() -> resolve(twoSites, "community"))
                .as("the same reference must not quietly start returning the other site's community")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A refusal has to say enough to act on: which key, how many times, and what to do. */
    @Test
    void aRefusalSaysWhatCollidedAndWhatToDo(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "s.yaml", """
                snmp:
                  core:
                    community: core-secret
                  edge:
                    community: edge-secret
                """);

        assertThatThrownBy(() -> resolve(file, "community"))
                .hasMessageContaining("'community'")
                .hasMessageContaining("declared 2 times")
                .hasMessageContaining("its own file");
    }

    /**
     * {@code Properties} accepts whitespace as a key/value separator alongside {@code =} and
     * {@code :}. An earlier version counted declarations with a regex that only knew the latter
     * two, so this file kept the defect entirely — the guard saw no declarations at all.
     */
    @Test
    void whitespaceSeparatedDeclarationsAreCounted(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "ws.properties", "community public\ncommunity other\n");

        assertThatThrownBy(() -> resolve(file, "community"))
                .hasMessageContaining("declared 2 times");
    }

    /**
     * A line continuation is folded into the previous value, so the continued line is not a
     * declaration however much it looks like one. Refusing here would break a file that resolves
     * unambiguously — and on the ClickHouse path that is a failed startup.
     */
    @Test
    void aFoldedLineContinuationIsNotADeclaration(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "cont.properties", "note=see \\\ncommunity: not-a-decl\ncommunity=real\n");

        assertThat(resolve(file, "community")).isEqualTo("real");
    }

    /** An escaped separator is part of the key, and the count has to follow the key it produces. */
    @Test
    void anEscapedSeparatorInAKeyIsCounted(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "esc.properties", "snmp\\.community=a\nsnmp\\.community=b\n");

        assertThatThrownBy(() -> resolve(file, "snmp.community"))
                .hasMessageContaining("declared 2 times");
    }

    /** {@code Properties} treats a bare CR as a line terminator; the count must agree. */
    @Test
    void carriageReturnOnlyLineEndingsAreCounted(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "cr.properties", "community=a\rcommunity=b\r");

        assertThatThrownBy(() -> resolve(file, "community"))
                .hasMessageContaining("declared 2 times");
    }

    /**
     * The check needs no format of its own, and this is the case that forces it. A tab is a YAML
     * syntax error, so a check that parsed the file as YAML first would fall back to the collapsing
     * reader and hand out the wrong secret — the defect, restored by its own fix. Counting what
     * {@code Properties} itself declares never fails, because it is the reader that would collapse
     * them.
     */
    @Test
    void aFileNoParserCanReadStillCannotResolveAmbiguously(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "tabs.yaml",
                "snmp:\n\tcore:\n\t\tcommunity: core-secret\n\tedge:\n\t\tcommunity: edge-secret\n");

        assertThatThrownBy(() -> resolve(file, "community"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared 2 times");
    }

    /** A duplicated key is a mistake in any format, including the one properties silently allows. */
    @Test
    void aDuplicatedPropertiesLineIsRefused(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "d.properties", "snmp.community=public\nsnmp.community=other\n");

        assertThatThrownBy(() -> resolve(file, "snmp.community"))
                .hasMessageContaining("declared 2 times");
    }

    /**
     * Everything a file already resolves must resolve identically. The check adds a refusal; it
     * must not touch how a value is read — no retyping, no comment stripping, no quote handling.
     */
    @Test
    void aSingleDeclarationIsReadExactlyAsBefore(@TempDir Path dir) throws IOException {
        for (final String value : new String[] {"public", "0755", "yes", "1e5", "pass #1", "a=b", "  padded"}) {
            assertThat(resolve(write(dir, "eq.properties", "snmp.community=" + value + "\n"), "snmp.community"))
                    .as("'%s' must survive verbatim through '='", value)
                    .isEqualTo(value.strip());
            assertThat(resolve(write(dir, "colon.properties", "snmp.community: " + value + "\n"), "snmp.community"))
                    .as("'%s' must survive verbatim through ':'", value)
                    .isEqualTo(value.strip());
        }
    }

    /** A commented-out declaration is not a declaration, in either comment syntax. */
    @Test
    void commentedDeclarationsAreNotCounted(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "c.properties", """
                # community=old-secret
                ! community=older-secret
                community=current
                """);

        assertThat(resolve(file, "community")).isEqualTo("current");
    }

    /** A key named only as part of a longer key is a different key. */
    @Test
    void aLongerKeyIsNotACollision(@TempDir Path dir) throws IOException {
        final Path file = write(dir, "s.yaml", """
                snmp:
                  community: wanted
                  community_backup: other
                  old_community: another
                """);

        assertThat(resolve(file, "community")).isEqualTo("wanted");
    }

    @Test
    void anAbsentKeyIsStillReportedAsMissing(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> resolve(write(dir, "s.properties", "a=b\n"), "nope"))
                .hasMessageContaining("not found");
    }

    /** Unchanged behaviour: no key means the whole file, trimmed. */
    @Test
    void aKeylessReferenceIsTheWholeFileTrimmed(@TempDir Path dir) throws IOException {
        assertThat(resolve(write(dir, "bare", "  s3cret\n"), null)).isEqualTo("s3cret");
    }
}
