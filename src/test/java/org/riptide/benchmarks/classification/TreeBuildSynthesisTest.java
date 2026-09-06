/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.classification;

import org.junit.jupiter.api.Test;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The synthesis behind {@link TreeBuildBenchmark}, gated on every build rather than only when
 * someone runs a benchmark.
 *
 * <p>The measured sizes in the operator docs are only worth what the clone is worth. A clone whose
 * ports collided with another's would deduplicate into fewer distinct thresholds, so the tree would
 * do less work than the rule count claims and the published figure would flatter the build at that
 * size. {@code cloneRuleset} checks that itself and throws — but nothing runs benchmarks, so
 * without this the check is only as good as someone remembering to run one.
 *
 * <p>None of this builds a tree: it counts rules and ports, which is why it is cheap enough to sit
 * in the suite while {@link TreeBuildBenchmark} deliberately does not (#746).
 */
class TreeBuildSynthesisTest {

    private static final int BUNDLED_RULES = 6248;
    private static final int BUNDLED_PREPROCESSED = 12496;

    /**
     * What a maintainer needs to hear when one of these reds. These counts are not internal to the
     * benchmark: docs/docs/deploy/operations.md quotes them, and quotes a build time against each,
     * so a legitimate ruleset edit does not just move a constant here — it invalidates a published
     * figure that nothing else will notice is wrong.
     */
    private static final String STALE_DOCS =
            "the shipped classification-rules.csv changed. docs/docs/deploy/operations.md"
                    + " (Supported ruleset size) quotes these counts and a measured build time for each,"
                    + " so re-measure with `make bench-jmh BENCH_TARGET=TreeBuildBenchmark` and update that"
                    + " section in the same commit as the ruleset change, then update the constants here";

    /**
     * The figure every published number is relative to; a changed CSV must move this row first.
     */
    @Test
    void theBundledRulesetIsTheSizeTheDocsQuote() {
        final var bundled = TreeBuildBenchmark.bundled();

        assertThat(bundled)
                .as(STALE_DOCS)
                .hasSize(BUNDLED_RULES);
        assertThat(TreeBuildBenchmark.preprocessedCount(bundled))
                .as("reversals included, as DefaultClassificationEngine.reload counts them. " + STALE_DOCS)
                .isEqualTo(BUNDLED_PREPROCESSED);
    }

    /**
     * The property the docs' x2 and x4 rows rest on: a clone multiplies the work, it does not
     * merely multiply the row count.
     */
    @Test
    void everyCloneMultipliesRulesPortsAndPreprocessedCount() {
        final var bundled = TreeBuildBenchmark.bundled();
        final var bundledPorts = TreeBuildBenchmark.distinctDstPorts(bundled).size();

        for (final int multiple : new int[] {1, 2, 4}) {
            final var cloned = TreeBuildBenchmark.cloneRuleset(bundled, multiple);

            assertThat(cloned)
                    .as("x%d rule count. %s", multiple, STALE_DOCS)
                    .hasSize(BUNDLED_RULES * multiple);
            assertThat(TreeBuildBenchmark.preprocessedCount(cloned))
                    .as("x%d preprocessed count — what Tree.of actually works on. %s", multiple, STALE_DOCS)
                    .isEqualTo(BUNDLED_PREPROCESSED * multiple);
            assertThat(TreeBuildBenchmark.distinctDstPorts(cloned))
                    .as("x%d distinct ports — a collision here is the failure that would understate the"
                            + " build cost without failing anything, so the x2 and x4 seconds in"
                            + " docs/docs/deploy/operations.md would be too low. Fix the remap before"
                            + " re-measuring. %s", multiple, STALE_DOCS)
                    .hasSize(bundledPorts * multiple);
        }
    }

    /**
     * The port space is the binding constraint, and the reason a constant per-clone offset was not
     * used: the bundled ports run to 49150, so bands that wide do not fit twice into 65535. The
     * residue-class remap fits, but only up to a point — and past it the benchmark must refuse
     * rather than measure a collided ruleset.
     */
    @Test
    void aMultipleThatCannotFitThePortSpaceIsRefused() {
        final var bundled = TreeBuildBenchmark.bundled();
        final var ports = TreeBuildBenchmark.distinctDstPorts(bundled).size();
        final int tooMany = (65535 / ports) + 1;

        assertThatThrownBy(() -> TreeBuildBenchmark.cloneRuleset(bundled, tooMany))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port space");
    }

    /**
     * The sibling of the collision guard, and the one that survives a CSV edit rather than a code
     * edit. Every published second was measured on one rule shape — a single integer destination
     * port and nothing else — because that is what the bundled ruleset is and what the port remap
     * knows how to clone. A rule with a source port, an address or a port range is not that shape:
     * remapping only its {@code dstPort} would leave the other condition identical across every
     * clone, so the clones would share thresholds and the measured build would be too cheap, in a
     * way no count check catches. It has to refuse.
     *
     * <p>This row exists because deleting the guard broke nothing: the bundled ruleset satisfies it,
     * so every other test in this class passes with it gone.
     */
    @Test
    void aRuleShapeTheRemapCannotCloneIsRefusedRatherThanMeasured() {
        final var offending = List.<UnaryOperator<DefaultRule.Builder>>of(
                b -> b.withSrcPort("1024"),
                b -> b.withSrcAddress("10.0.0.0/8"),
                b -> b.withDstAddress("10.0.0.0/8"),
                b -> b.withDstPort("1024-2048"),
                b -> b.withDstPort(""),
                b -> b.withExporterFilter("catinabox"));

        for (final var mutate : offending) {
            final List<Rule> ruleset = List.<Rule>of(mutate.apply(DefaultRule.builder()
                    .withName("shape-guard-probe")
                    .withProtocol("tcp")
                    .withDstPort(22)
                    .withOmnidirectional(true)
                    .withPosition(0)).build());

            assertThatThrownBy(() -> TreeBuildBenchmark.cloneRuleset(ruleset, 2))
                    .as("a rule the remap cannot clone must stop the synthesis, not be cloned unchanged")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shape-guard-probe")
                    .hasMessageContaining("single integer destination port")
                    .hasMessageContaining("operations.md");
        }
    }

    /** A bad {@code @Param} should name the parameter, not surface a raw parse failure. */
    @Test
    void aNonPositiveMultipleIsRefusedNamingItsOwnCause() {
        final var bundled = TreeBuildBenchmark.bundled();

        assertThatThrownBy(() -> TreeBuildBenchmark.cloneRuleset(bundled, 0))
                .as("x0 clones to an empty ruleset, which passes both count checks — 0 is 0 times"
                        + " anything — so it would measure building nothing and report it as a size")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
