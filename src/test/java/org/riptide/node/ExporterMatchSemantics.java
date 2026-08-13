/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.riptide.pipeline.ExporterIdentity;

import java.util.List;
import java.util.Optional;

/**
 * Implementation-agnostic seam for the exporter-matching characterisation suite.
 *
 * <p>The suite pins riptide's matching semantics — longest prefix wins, an
 * observation-domain pin beats wildcard, a bare host address is most specific, exact
 * ties fail validation naming both entries — without naming the implementation that
 * provides them. A refactor that changes the suite's outcome is wrong by definition;
 * a new implementation retargets the suite by providing another {@code
 * ExporterMatchSemantics}, with no test-body edits.</p>
 */
interface ExporterMatchSemantics {

    /**
     * Builds serving state from entries in declaration order.
     *
     * @throws IllegalStateException on invalid configuration, naming the offending
     *         entries
     */
    Matcher build(List<Entry> entries);

    /**
     * One matching entry: a name, a subnet (prefix or bare host address), and an
     * optional observation-domain pin ({@code null} = wildcard).
     */
    record Entry(String name, String subnet, Long observationDomainPin) {
    }

    /** Serving state: resolves an exporter identity to the matching entry's name. */
    interface Matcher {
        Optional<String> matchedName(ExporterIdentity identity);
    }
}
