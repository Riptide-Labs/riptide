/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Container image references for the IT and e2e tiers, read from the stub
 * Dockerfiles under {@code .github/e2e-images/} so Dependabot's docker ecosystem
 * keeps them current in exactly one place. Test JVMs run with the module basedir
 * as working directory, so the relative path resolves.
 */
public final class ContainerImages {

    private ContainerImages() {
    }

    public static String nl6() {
        return fromLine("nl6.Dockerfile");
    }

    public static String clickhouse() {
        return fromLine("clickhouse.Dockerfile");
    }

    private static String fromLine(final String dockerfile) {
        final Path path = Path.of(".github", "e2e-images", dockerfile);
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line.startsWith("FROM "))
                    .findFirst()
                    .map(line -> line.substring("FROM ".length()).trim())
                    .orElseThrow(() -> new IllegalStateException("No FROM line in " + path));
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read image reference from " + path, e);
        }
    }
}
