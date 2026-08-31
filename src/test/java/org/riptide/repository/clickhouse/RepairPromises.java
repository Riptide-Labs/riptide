/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import java.util.regex.Pattern;

/**
 * The wordings that promise a rollup repair, shared by every test that must refuse them (#654).
 *
 * <p>Its own class, and deliberately free of Testcontainers, so a unit test can read the pattern
 * without initialising an integration-test class and constructing its {@code @Container} field.</p>
 */
final class RepairPromises {

    private RepairPromises() {
    }

    /**
     * Matched as a claim, not as one spelling, because the defect was prose and prose gets reworded.
     *
     * <p>Pinned phrasings: "until (it is) repaired", "will be repaired", "repair is deferred",
     * "repairs itself", "repaired on the next start", "to have it repaired/fixed", "to repair it".
     * The last two were added after a #657 draft wrote "Run 'riptide onboard' … to have it
     * repaired" and this pattern did not match it.</p>
     *
     * <p><b>The promise is matched, not the command.</b> Naming {@code riptide onboard} is correct
     * where it genuinely does the thing — recreating an absent view (#587) — and an earlier draft
     * banned the command outright and failed that test. {@code FlowsSchema}'s "drop and re-create
     * the rollup to have it rebuilt" is likewise a true remedy and must not match.</p>
     */
    static final Pattern PROMISES_A_REPAIR = Pattern.compile(
            "until (it is |it has been )?repaired|will be repaired|repair is deferred"
                    + "|repairs (itself|themselves)|repaired on the next start"
                    + "|to have it (repaired|fixed)|to repair it",
            Pattern.CASE_INSENSITIVE);
}
