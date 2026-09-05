/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.Protocol;
import org.riptide.classification.Protocols;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.DefaultClassificationEngine;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.classification.internal.value.PortValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the decision tree the bundled ruleset builds, shape and answers both.
 *
 * <p>Tree construction is a performance surface — see #746 — and every change to it is claimed to leave
 * the tree alone. That claim is only worth as much as an instrument that can contradict it. The split
 * criterion, the candidate set, the {@code maximumSize} filter and the encounter order that breaks ties
 * all decide <em>which</em> threshold wins at each node, and any of them moving silently produces a
 * different, still plausible, still green tree.
 *
 * <p>Two things are pinned. Every field of the root {@link Tree.Info} fixes the shape. A digest over the
 * sweep below fixes what the tree decides, which is the part operators see. What each one is actually
 * known to catch is recorded on the two rows, because measuring that turned out to matter: the shape pin
 * caught both tree perturbations tried against it and the answer pin caught neither.
 *
 * <h2>This row must never build the tree itself</h2>
 *
 * <p>The tree comes from a {@link DefaultClassificationEngine} over the process-wide
 * {@code DecisionTreeCache}, exactly as {@code DefaultClassificationEngineTest} takes it, and never from
 * a direct {@code Tree.of} call. #707 removed the suite's second bundled build and a direct call here
 * silently puts it back — silently because the build counter is {@code grep -c "rules    : 6248"} over a
 * suite log, a line emitted by the engine and not by {@code Tree.of}, so a bypassing build costs 30-40 s
 * without moving the count. That is not hypothetical: it is what the first version of this class did.
 *
 * <h2>Regenerating the constants</h2>
 *
 * <p>They are recorded, not derived. To regenerate, run
 * {@code mvn -o test -Dtest=BundledRulesetTreeIdentityTest} and read the expected/actual pair out of
 * each failure. Every constant appears in exactly one assertion, so each failure names the one it
 * belongs to; AssertJ stops a row at its first failure, so a regeneration takes one run per moved
 * constant.
 *
 * <p>A maintainer facing a failure has to separate two cases, and the rows are ordered to help. If
 * {@code classification-rules.csv} changed on purpose, the rule and preprocessed counts asserted first
 * move too, and every constant here is expected to move together — regenerate them in one commit that
 * says which ruleset change caused it. If those two counts are unchanged and the fingerprint moved, the
 * ruleset is the same and the tree built from it is not: that is a regression in tree construction and
 * the constants must not be regenerated to make it green.
 *
 * <p>A third case is neither: the numbers are coupled to the iteration order of the candidate-threshold
 * {@code HashSet}s, so a JDK that reordered them would move the fingerprint with no change here. Nothing
 * enforces the JDK — the build sets {@code <release>25</release>}, which is a language level, and the CI
 * workflows pin the runtime to 25, but there is no {@code requireJavaVersion} enforcer, so a developer on
 * a newer JDK is not stopped. A fingerprint failure that reproduces only off CI's JDK is this case.
 */
public class BundledRulesetTreeIdentityTest {

    /** Every field of the root Info, as read at 150552d0 and unchanged by #746. */
    private static final int MIN_DEPTH = 2;
    private static final int MAX_DEPTH = 14;
    private static final int SUM_DEPTH = 184337;
    private static final long MIN_LEAF_SIZE = 0;
    private static final long MAX_LEAF_SIZE = 3;
    private static final long SUM_LEAF_SIZE = 12498;
    private static final int NODES = 7764;
    private static final int LEAVES = 15530;
    private static final int CHOICES = 1;
    private static final int MIN_COMP = 4;
    private static final int MAX_COMP = 26;
    private static final int SUM_COMP = 228480;

    /**
     * SHA-256 over {@code "<index>=<answer>\n"} for every request of {@link #sweep(Consumer)}, in order,
     * UTF-8. Recomputed by any run of this test; a mismatch means at least one answer moved.
     */
    private static final String ANSWER_DIGEST = "86b14bf06a8aaf967f3bd215b3b7322a9fe9d3295f0430774002a97f796d15ed";

    private static final int SAMPLE_SIZE = 123872;
    private static final int CLASSIFIED = 48723;
    private static final int PROBED_PORTS = 7542;

    private static final List<String> PROTOCOLS = List.of("tcp", "udp", "sctp", "dccp");

    /**
     * Ports paired against each other to reach the answer path a one-port-at-a-time sweep cannot. Every
     * bundled rule constrains a single port and is reversed into the tree, so a request with one port
     * pinned to 0 can match at most one rule and the priority ordering in {@code Tree.classify} never
     * decides anything. With both ports live, two rules can match one request and the {@code
     * matchedAspects} tie-break is what picks the answer.
     */
    private static final List<Integer> PAIRED = List.of(
            0, 20, 21, 22, 23, 25, 53, 67, 68, 69, 80, 110, 123, 137, 143, 161,
            389, 443, 445, 465, 514, 587, 636, 993, 995, 1080, 1194, 1433, 1521,
            3306, 3389, 5060, 5432, 5672, 6379, 8080, 8443, 9090, 9200, 27017);

    private static Tree tree;
    private static int ruleCount;
    private static int preprocessedCount;
    private static NavigableSet<Integer> probedPorts;

    private static final ClassificationRuleProvider BUNDLED = () -> {
        try (var stream = BundledRulesetTreeIdentityTest.class.getResourceAsStream("/classification-rules.csv")) {
            return new CsvImporter().parse(stream, true);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the bundled ruleset", e);
        }
    };

    /**
     * Takes the tree through the engine and the shared cache, so this row rides #707's reuse instead of
     * adding a build to the suite. The 5-minute bound is for the case where this row is the one class
     * that builds — the same instrumented cost, and the same reasoning, as the bundled rows in
     * {@code DefaultClassificationEngineTest} and {@code ClassificationRuleReloaderTest}.
     */
    @BeforeAll
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    static void takeTheBundledTreeFromTheEngine() throws InterruptedException {
        final var rules = BUNDLED.getRules();
        ruleCount = rules.size();

        var preprocessed = 0;
        final var ports = new TreeSet<Integer>();
        for (final var rule : rules) {
            preprocessed += rule.canBeReversed() ? 2 : 1;
            collectProbePorts(rule, ports);
        }
        preprocessedCount = preprocessed;
        probedPorts = ports;

        tree = new DefaultClassificationEngine(BUNDLED).getTree();
    }

    /**
     * The sweep probes each rule's own port boundaries rather than every port. Volume over one axis adds
     * requests that land in the same leaves; the values a rule's range begins and ends at, and their
     * neighbours, are the ones the tree actually splits on, so they are where a differently built tree
     * would answer differently.
     */
    private static void collectProbePorts(final Rule rule, final TreeSet<Integer> into) {
        into.add(0);
        into.add(65535);
        for (final var value : new PortValue[] {
                rule.hasSrcPortDefinition() ? PortValue.of(rule.getSrcPort()) : null,
                rule.hasDstPortDefinition() ? PortValue.of(rule.getDstPort()) : null}) {
            if (value == null) {
                continue;
            }
            for (final var range : value.getPortRanges()) {
                for (final var edge : new int[] {range.getBegin(), range.getEnd()}) {
                    for (final var delta : new int[] {-1, 0, 1}) {
                        final var port = edge + delta;
                        if (port >= 0 && port <= 65535) {
                            into.add(port);
                        }
                    }
                }
            }
        }
    }

    /**
     * The sweep, and the order the digest is taken in. Requests are handed over one at a time rather
     * than collected: the sample does not need to exist all at once.
     */
    private static void sweep(final Consumer<ClassificationRequest> consumer) {
        // one port at a time, both directions, because the ruleset is reversed into the tree
        for (final var name : PROTOCOLS) {
            final Protocol protocol = Protocols.getProtocol(name);
            assertThat(protocol).as("the sweep must name protocols the ruleset uses: %s", name).isNotNull();
            for (final var port : probedPorts) {
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withSrcPort(0).withDstPort(port).build());
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withSrcPort(port).withDstPort(0).build());
            }
        }

        // both ports live, so more than one rule can match and the priority merge decides
        for (final var name : List.of("tcp", "udp")) {
            final Protocol protocol = Protocols.getProtocol(name);
            for (final var src : PAIRED) {
                for (final var dst : PAIRED) {
                    consumer.accept(ClassificationRequest.builder()
                            .withProtocol(protocol).withSrcPort(src).withDstPort(dst).build());
                }
            }
        }

        // one port absent rather than zero, which is what reaches the NA branches: a request with no
        // source port compares NA against every source-port threshold, so it is routed by the "na"
        // child instead of by lt/eq/gt.
        //
        // The matching shape for protocols - a request with no protocol at all, which would reach the
        // na children of the protocol thresholds serving the eleven protocol-less bundled rules - is
        // deliberately absent. It cannot be swept: classifying a request whose protocol is null throws
        // NPE out of ProtocolMatcher.matches, which dereferences getProtocol() unguarded. That is a
        // production defect rather than a limit of this test, it is not #746's, and a row here that
        // asserted the current behaviour would be pinning the crash.
        for (final var name : PROTOCOLS) {
            final Protocol protocol = Protocols.getProtocol(name);
            for (final var port : probedPorts) {
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withDstPort(port).build());
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withSrcPort(port).build());
            }
        }
    }

    /**
     * The shape pin. This is the row that catches a changed tree: measured against two perturbations of
     * the winner selection — a counting path that disagreed with the list path on one bucket, and a
     * tie broken the other way — this failed on both and the answer row below failed on neither.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void theBundledRulesetBuildsTheTreeItHasAlwaysBuilt() {
        // the ruleset this fingerprint is of - without this the pins below could be met by a
        // truncated or mis-parsed CSV, and this is the pair that separates a deliberate ruleset
        // change from a tree-construction regression
        assertThat(ruleCount).as("rules parsed from the bundled CSV").isEqualTo(6248);
        assertThat(preprocessedCount).as("preprocessed rules, reversals included").isEqualTo(12496);

        final var info = tree.info;
        assertThat(info.leaves).as("leaves").isEqualTo(LEAVES);
        assertThat(info.nodes).as("nodes").isEqualTo(NODES);
        assertThat(info.maxDepth).as("maxDepth").isEqualTo(MAX_DEPTH);
        assertThat(info.minDepth).as("minDepth").isEqualTo(MIN_DEPTH);
        assertThat(info.sumDepth).as("sumDepth").isEqualTo(SUM_DEPTH);
        assertThat(info.minLeafSize).as("minLeafSize").isEqualTo(MIN_LEAF_SIZE);
        assertThat(info.maxLeafSize).as("maxLeafSize").isEqualTo(MAX_LEAF_SIZE);
        assertThat(info.sumLeafSize).as("sumLeafSize").isEqualTo(SUM_LEAF_SIZE);
        assertThat(info.choices).as("choices").isEqualTo(CHOICES);
        assertThat(info.minComp).as("minComp").isEqualTo(MIN_COMP);
        assertThat(info.maxComp).as("maxComp").isEqualTo(MAX_COMP);
        assertThat(info.sumComp).as("sumComp").isEqualTo(SUM_COMP);
    }

    /**
     * The answer pin. It fixes what operators see, and it is the only row that would catch a tree which
     * kept its shape and changed its answers. It is not a second opinion on the shape: neither
     * perturbation the shape row above caught was caught here.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void everyAnswerOverTheSweepIsTheAnswerItHasAlwaysBeen() throws NoSuchAlgorithmException {
        // readable first, so a reader sees a named answer before an opaque hash
        assertThat(tree.classify(ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("udp")).withDstPort(123).build())).isEqualTo("ntp");
        assertThat(tree.classify(ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("tcp")).withDstPort(22).build())).isEqualTo("ssh");

        final var digest = MessageDigest.getInstance("SHA-256");
        final var index = new int[] {0};
        final var classified = new int[] {0};
        final var answers = new ArrayList<String>();
        sweep(request -> {
            final var answer = tree.classify(request);
            if (answer != null) {
                classified[0]++;
                if (answers.size() < 4) {
                    answers.add(answer);
                }
            }
            digest.update((index[0]++ + "=" + answer + "\n").getBytes(StandardCharsets.UTF_8));
        });

        assertThat(probedPorts).as("port boundaries derived from the ruleset").hasSize(PROBED_PORTS);
        assertThat(index[0]).as("requests swept").isEqualTo(SAMPLE_SIZE);
        // positive controls on the digest: a tree that answered null to everything, or a sweep that
        // degenerated, would still produce a stable hash
        assertThat(classified[0]).as("requests the tree gave a name to").isEqualTo(CLASSIFIED);
        assertThat(answers).as("the sweep must reach real classifications, not just nulls").isNotEmpty();

        assertThat(HexFormat.of().formatHex(digest.digest()))
                .as("SHA-256 over every answer of the sweep, in order")
                .isEqualTo(ANSWER_DIGEST);
    }
}
