/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two states the rollup view-creation policy must get right, neither of which an integration
 * test can reach (#470).
 *
 * <p>A refused rollup needs hand-built DDL that no riptide path produces, and an unread catalog
 * needs a transport failure — an under-privileged user gets filtered rows from
 * {@code system.tables}, not an error. Both were shipped unguarded once and found by review rather
 * than by a test, which is why the decision now lives in a function instead of in a loop condition.
 */
class ViewCreationPolicyTest {

    private static final List<String> ROLLUPS = List.of("a_1m", "b_1m", "c_1m", "d_1m");

    /** The ordinary case: build every view, decline nothing extra. */
    @Test
    void aReadableCatalogWithNoRefusalsBuildsEveryView() {
        final var plan = ClickhouseRepository.planViewCreation(true, Set.of(), ROLLUPS);

        assertThat(plan.create()).containsExactlyElementsOf(ROLLUPS);
        assertThat(plan.decline()).isEmpty();
    }

    /**
     * A refused rollup gets no view, and needs no extra decline — being refused already declines it.
     *
     * <p>The CREATE would not fail here, which is the trap: a target carrying the rate outside its
     * sorting key has every column the SELECT names, so riptide would build the very view the
     * refusal exists to prevent, and a {@code SummingMergeTree} would then sum the rate itself.
     */
    @Test
    void aRefusedRollupGetsNoView() {
        final var plan = ClickhouseRepository.planViewCreation(true, Set.of("b_1m"), ROLLUPS);

        assertThat(plan.create()).containsExactly("a_1m", "c_1m", "d_1m");
        assertThat(plan.decline()).isEmpty();
    }

    /**
     * An unread catalog builds nothing AND declines everything it skipped.
     *
     * <p>The half that was missing. A target whose columns and sorting key are current with no view
     * reads as UNVERIFIABLE, which is deliberately not declined — so skipping silently would publish
     * four empty tables as usable and answer every query spanning an hour or more with zero traffic,
     * for the lifetime of the process.
     */
    @Test
    void anUnreadCatalogDeclinesEveryViewItDidNotBuild() {
        final var plan = ClickhouseRepository.planViewCreation(false, Set.of(), ROLLUPS);

        assertThat(plan.create()).isEmpty();
        assertThat(plan.decline())
                .as("a skipped view that nothing records leaves an empty rollup in the query path")
                .containsExactlyInAnyOrderElementsOf(ROLLUPS);
    }

    /** And an unread catalog declines the refused ones too — it cannot tell them apart. */
    @Test
    void anUnreadCatalogDeclinesRefusedRollupsAsWell() {
        final var plan = ClickhouseRepository.planViewCreation(false, Set.of("b_1m"), ROLLUPS);

        assertThat(plan.create()).isEmpty();
        assertThat(plan.decline()).containsExactlyInAnyOrderElementsOf(ROLLUPS);
    }
}
