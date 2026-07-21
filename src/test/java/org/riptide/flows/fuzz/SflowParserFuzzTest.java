/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.riptide.flows.parser.sflow.proto.Datagram;

import java.time.Instant;

/**
 * Fuzzes the sFlow parse surface: the XDR datagram decode and its {@code buildFlows} sample walk.
 * sFlow carries no cross-packet state, so one datagram per run covers it. The nested XDR
 * structure (samples containing records containing raw-packet headers) is the interesting target —
 * length-prefixed sub-structures are where an attacker-controlled count over-allocates or over-reads.
 */
class SflowParserFuzzTest {

    @FuzzTest
    void parse(final byte[] data) throws Throwable {
        try {
            final Datagram datagram = new Datagram(FuzzSupport.buffer(data));
            datagram.buildFlows(Instant.EPOCH).forEach(flow -> { });
        } catch (final Throwable t) {
            if (!FuzzSupport.isDesignedRejection(t)) {
                throw t;
            }
        }
    }
}
