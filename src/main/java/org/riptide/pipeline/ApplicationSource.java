/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

/**
 * Which rung named a flow's {@code application}. Each constant carries the token written to the
 * {@code applicationSource} column; the token is the stable identifier, as with
 * {@link org.riptide.flows.parser.data.Flow.SamplingProvenance}.
 */
public enum ApplicationSource {
    /** The exporter's own application table named the record's applicationId. */
    Exporter("exporter"),
    /** A port or address rule matched. */
    Rules("rules"),
    /** Nothing named it. */
    None("none");

    private final String token;

    ApplicationSource(final String token) {
        this.token = token;
    }

    public String token() {
        return this.token;
    }
}
