/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

/**
 * The characterisation contract bound to the consumer path the cutover created, so the
 * suite finally covers what a flow actually goes through rather than stopping at the view.
 */
class EnricherExporterSemanticsTest extends ExporterMatchSemanticsContract {

    @Override
    protected ExporterMatchSemantics semantics() {
        return new EnricherExporterMatchSemantics();
    }
}
