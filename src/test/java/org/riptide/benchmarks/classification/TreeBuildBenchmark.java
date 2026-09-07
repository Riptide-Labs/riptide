/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.classification;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.classification.internal.decision.PreprocessedRule;
import org.riptide.classification.internal.decision.Tree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures what one {@code Tree.of} build costs, at the shipped ruleset size and at synthesised
 * multiples of it. This is the boot cost an operator pays once per collector start and once per
 * accepted rule reload, and the measurement behind the supported ruleset size stated in
 * {@code docs/docs/deploy/operations.md}. See issue 746.
 *
 * <h2>What transfers off this machine and what does not</h2>
 *
 * <p>Only the {@code bundled} row is a real ruleset. Every {@code synthetic-} row is the bundled
 * ruleset cloned with remapped ports, and a clone has different threshold cardinality than a real
 * ruleset of the same size would: real rulesets cluster on well-known ports, a clone spreads
 * evenly. So the <em>shape</em> of the growth across the synthetic rows transfers and the absolute
 * seconds do not. {@code synthetic-x1} exists to make that measurable rather than asserted — it is
 * the same rule count as {@code bundled} built the synthetic way, so the gap between those two rows
 * is the synthesis artefact itself.
 *
 * <p>Nothing here transfers across machines either. {@code Tree.of} scores split candidates on a
 * {@code parallel()} stream, so the score depends on the common ForkJoinPool's parallelism and thus
 * on the core count. Quote a number with the machine beside it.
 *
 * <h2>Why {@code SingleShotTime}</h2>
 *
 * <p>{@code AverageTime}, the mode the parser benchmarks use, is wrong for a multi-second
 * operation: it would run for many minutes per row and it would report a JIT-warmed steady state
 * that a boot never reaches. Boot is one cold build, which is what {@code SingleShotTime} reports
 * one of per iteration.
 *
 * <p>It still is not fully cold, because JMH's warmup iterations run real builds, so the reported
 * score is a warm one. The closest cold number this harness produces is the "# Warmup Iteration 1"
 * line JMH prints per fork, and it comes out of this same run rather than out of a second harness
 * — quoting two harnesses' numbers side by side is the confusion #707 was filed on. Do not assume
 * the cold shot is the larger of the two: measured, it ran about 20% above the warm score at the
 * bundled size and inside run-to-run noise at the synthetic ×2 and ×4 sizes.
 *
 * <h2>Why the counts are asserted</h2>
 *
 * <p>A clone whose ports collided with the original would deduplicate into fewer distinct
 * thresholds and measure less work than its rule count claims, which is the failure that makes a
 * synthesised size worthless. The preprocessed count and the distinct-port count are both checked
 * against the expected multiple in {@link #cloneRuleset(List, int)}, and a mismatch throws there
 * rather than producing a smaller measurement. {@code TreeBuildSynthesisTest} runs those same
 * checks in the suite, so they hold whether or not anyone runs a benchmark.
 *
 * <h2>What this does not measure</h2>
 *
 * <p>{@code Tree.of} alone. {@code DefaultClassificationEngine.reload} also spans reading the rules
 * from their resource and the preprocess loop that turns them into the list handed here, and
 * {@link #preprocess()} deliberately puts both outside the measured region. So the score is a lower
 * bound on the reload, not the reload. The whole thing is already timed in production, by the
 * {@code reload} Timer and by the "calculated flow classification decision tree" INFO line the
 * engine logs, and an operator wanting the number for their own ruleset should read those rather
 * than interpolate this table.
 *
 * <p>{@code @Threads(1)} is not decoration: {@code @State(Scope.Benchmark)} shares one instance
 * across threads and {@link #preprocessed} is rewritten per invocation, so a {@code -t N} run —
 * and {@code -t} is exactly the kind of flag {@code BENCH_OPTS} carries — would have threads
 * overwriting each other's input and would report lock and allocator contention as build cost.
 */
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g"})
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class TreeBuildBenchmark {

    private static final String SYNTHETIC_PREFIX = "synthetic-x";

    /**
     * The ruleset to build. {@code bundled} is the shipped CSV verbatim and the only transferable
     * figure; the {@code synthetic-xN} rows are that CSV cloned N times with remapped ports.
     */
    @Param({"bundled", "synthetic-x1", "synthetic-x2", "synthetic-x4"})
    public String ruleset;

    private List<Rule> rules;

    /** Rebuilt per invocation by {@link #preprocess()}; the list {@code Tree.of} consumes. */
    private List<PreprocessedRule> preprocessed;

    /**
     * The shape of the first tree this trial built, reported by {@link #reportShape()}. Recorded
     * here rather than derived elsewhere because the shape is part of what the operator docs quote,
     * and a number the documented command cannot reproduce is not reproducible. Every build in a
     * trial produces the same shape, so the first one is the shape.
     */
    private Tree.Info shape;

    /**
     * Per-rule verdicts the build spent (#768) — deterministic, so unlike the times beside it this
     * yields an exponent that owes nothing to the machine, the core count or the JIT.
     */
    private long verdicts;

    @Setup(Level.Trial)
    public void synthesise() {
        final var bundled = bundled();
        if (ruleset.equals("bundled")) {
            rules = bundled;
        } else if (ruleset.startsWith(SYNTHETIC_PREFIX)) {
            rules = cloneRuleset(bundled, multipleOf(ruleset));
        } else {
            throw new IllegalArgumentException(unknownRuleset(ruleset));
        }
        shape = null;
        verdicts = 0;
        System.out.printf("# ruleset %s: %d rules, %d preprocessed, %d distinct dstPort values%n",
                ruleset, rules.size(), preprocessedCount(rules), distinctDstPorts(rules).size());
    }

    /**
     * Reports the built tree beside the time it took, in the fields the engine's own
     * "calculated flow classification decision tree" INFO line uses, so the two are comparable.
     * The depth and comparison counts grow with the ruleset too, and the operator docs say by how
     * much — this is where those figures come from.
     */
    @TearDown(Level.Trial)
    public void reportShape() {
        if (shape == null) {
            return;
        }
        System.out.printf(Locale.ROOT,
                "# ruleset %s tree: nodes=%d leaves=%d maxDepth=%d avgDepth=%.2f"
                        + " maxComp=%d avgComp=%.2f%n",
                ruleset, shape.nodes, shape.leaves, shape.maxDepth,
                (double) shape.sumDepth / shape.leaves, shape.maxComp,
                (double) shape.sumComp / shape.leaves);
        // The deterministic half of this benchmark (#768). Unlike the score beside it, this number owes
        // nothing to the machine, the core count or the JIT, so the ratio between two sizes is an
        // exponent anyone can reproduce exactly. Locale.ROOT because these numbers are transcribed into
        // docs/docs/deploy/operations.md and compared across machines; a default-locale run groups them
        // with '.' in half of Europe, which reads as a decimal point next to the %.2f line above.
        System.out.printf(Locale.ROOT,
                "# ruleset %s work: %,d per-rule verdicts for %,d preprocessed rules%n",
                ruleset, verdicts, preprocessedCount(rules));
    }

    private static int multipleOf(final String ruleset) {
        final var suffix = ruleset.substring(SYNTHETIC_PREFIX.length());
        try {
            return Integer.parseInt(suffix);
        } catch (final NumberFormatException e) {
            // without this the sibling branch's message never appears: a bad suffix reports a raw
            // NumberFormatException naming the suffix and not the parameter it came from
            throw new IllegalArgumentException(unknownRuleset(ruleset), e);
        }
    }

    private static String unknownRuleset(final String ruleset) {
        return "unknown ruleset '%s': expected \"bundled\" or \"%s<N>\" with N a positive integer"
                .formatted(ruleset, SYNTHETIC_PREFIX);
    }

    /**
     * Rebuilds the input for every measured build. {@code Tree.of} does not mutate its input, so
     * this is not required for correctness — it is here so that no derived state (the per-rule
     * threshold sets, and the hash order they iterate in) is carried from one measured build into
     * the next. Fixture time at {@code Level.Invocation} is excluded from the score.
     */
    @Setup(Level.Invocation)
    public void preprocess() {
        preprocessed = preprocess(rules);
    }

    @Benchmark
    public void buildTree(final Blackhole blackhole) throws InterruptedException {
        final var work = new LongAdder();
        final var tree = Tree.of(preprocessed, work);
        if (shape == null) {
            shape = tree.info;
            verdicts = work.sum();
        }
        blackhole.consume(tree);
    }

    static List<Rule> bundled() {
        try (var stream = TreeBuildBenchmark.class.getResourceAsStream("/classification-rules.csv")) {
            return new CsvImporter().parse(stream, true);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the bundled ruleset", e);
        }
    }

    /**
     * Clones the ruleset {@code multiple} times, giving each clone its own residue class of the
     * port space so no two clones can share a port threshold.
     *
     * <p>A constant offset per clone — the obvious way to "shift the ports" — does not work here.
     * The bundled ports run to 49150, and four bands that wide do not fit in 65535, so clones would
     * have had to overlap and collide. Ranking instead is what makes the disjointness a property of
     * the construction rather than of a lucky offset: the bundled ruleset has 5,990 distinct ports,
     * so a port at rank {@code r} in clone {@code c} becomes {@code r * multiple + c + 1} and the
     * whole family fits under 65535 up to a multiple of 10.
     */
    static List<Rule> cloneRuleset(final List<Rule> bundled, final int multiple) {
        // ahead of the port-space check, which a non-positive multiple passes trivially — and an
        // empty ruleset then passes both count checks too (0 is 0 times anything), so the run would
        // measure building nothing and report it as a size
        if (multiple < 1) {
            throw new IllegalArgumentException("a ruleset multiple must be at least 1, not " + multiple);
        }
        final var ports = distinctDstPorts(bundled);
        if ((long) ports.size() * multiple > 65535) {
            throw new IllegalStateException("cannot fit %d distinct ports x%d into the port space"
                    .formatted(ports.size(), multiple));
        }
        final var rank = new HashMap<String, Integer>();
        for (final var port : ports) {
            rank.put(port, rank.size());
        }

        final var cloned = new ArrayList<Rule>(bundled.size() * multiple);
        for (var clone = 0; clone < multiple; clone++) {
            for (final var rule : bundled) {
                cloned.add(DefaultRule.builder()
                        .withName(rule.getName() + "-" + clone)
                        .withProtocol(rule.getProtocol())
                        .withDstPort(rank.get(rule.getDstPort()) * multiple + clone + 1)
                        .withOmnidirectional(rule.isOmnidirectional())
                        .withPosition(cloned.size())
                        .build());
            }
        }

        // A collided clone would measure less work than its rule count claims. Fail here rather
        // than report a smaller number as if it were the size asked for.
        final var expectedPreprocessed = (long) preprocessedCount(bundled) * multiple;
        final var actualPreprocessed = preprocessedCount(cloned);
        if (actualPreprocessed != expectedPreprocessed) {
            throw new IllegalStateException("x%d preprocessed to %d rules, expected %d"
                    .formatted(multiple, actualPreprocessed, expectedPreprocessed));
        }
        final var expectedPorts = (long) ports.size() * multiple;
        final var actualPorts = distinctDstPorts(cloned).size();
        if (actualPorts != expectedPorts) {
            throw new IllegalStateException("x%d has %d distinct ports, expected %d — the clones collided"
                    .formatted(multiple, actualPorts, expectedPorts));
        }
        return cloned;
    }

    /**
     * The bundled ruleset's one and only condition shape: a destination port, a protocol list, and
     * nothing else. The port remap in {@link #cloneRuleset(List, int)} is written for exactly that
     * shape, so a ruleset that grew a source port, an address or a range would be silently remapped
     * into something else — and, more to the point, the seconds in the operator docs would then
     * describe a rule shape the ruleset no longer has. Refuse instead;
     * {@code TreeBuildSynthesisTest} pins the refusal.
     */
    static TreeSet<String> distinctDstPorts(final List<Rule> rules) {
        final var ports = new TreeSet<String>();
        for (final var rule : rules) {
            if (rule.hasSrcPortDefinition() || rule.hasSrcAddressDefinition()
                    || rule.hasDstAddressDefinition() || rule.hasExporterFilterDefinition()
                    || !rule.hasDstPortDefinition() || !rule.getDstPort().matches("\\d+")) {
                throw new IllegalStateException(
                        "rule '" + rule.getName() + "' does not constrain a single integer destination port and"
                                + " nothing else, which is the only rule shape this synthesis can clone and the"
                                + " only shape the build times in docs/docs/deploy/operations.md"
                                + " (Supported ruleset size) were measured on. Those figures no longer describe"
                                + " this ruleset: extend the remap to the new shape, re-measure with"
                                + " `make bench-jmh BENCH_TARGET=TreeBuildBenchmark`, and update that section.");
            }
            ports.add(rule.getDstPort());
        }
        return ports;
    }

    /** Reversals included, exactly as {@code DefaultClassificationEngine.reload} counts them. */
    static int preprocessedCount(final List<Rule> rules) {
        var count = 0;
        for (final var rule : rules) {
            count += rule.canBeReversed() ? 2 : 1;
        }
        return count;
    }

    private static List<PreprocessedRule> preprocess(final List<Rule> rules) {
        final var preprocessed = new ArrayList<PreprocessedRule>(rules.size() * 2);
        for (final var rule : rules) {
            final var preprocessedRule = PreprocessedRule.of(rule);
            preprocessed.add(preprocessedRule);
            if (rule.canBeReversed()) {
                preprocessed.add(preprocessedRule.reverse());
            }
        }
        return preprocessed;
    }
}
