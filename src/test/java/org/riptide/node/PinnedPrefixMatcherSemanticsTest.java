/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

/** The characterisation contract bound directly to the standalone trie component. */
class PinnedPrefixMatcherSemanticsTest extends ExporterMatchSemanticsContract {

    @Override
    protected ExporterMatchSemantics semantics() {
        return new PinnedPrefixMatcherMatchSemantics();
    }
}
