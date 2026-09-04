/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.Protocol;
import org.riptide.classification.Protocols;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.csv.CsvImporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
 * <p>So two things are pinned here. Every field of the root {@link Tree.Info} fixes the shape. A digest
 * over half a million classification answers fixes what the tree actually decides, which is the part
 * operators see. The shape pin catches a rearranged tree that happens to answer the same; the answer pin
 * catches a tree of the same shape that answers differently.
 *
 * <p>The numbers below are not derived, they are recorded — they were read off the implementation at
 * commit {@code 150552d0}, before the scoring change of #746, and re-read after it. A future change that
 * moves them has changed the tree, and the question to ask is whether it meant to. They are also coupled
 * to the iteration order of the candidate-threshold {@code HashSet}s, so a JDK that reorders those would
 * move them too; the repo pins Java 25.
 */
public class BundledRulesetTreeIdentityTest {

    /** Every field of the root Info, as the pre-#746 implementation produced them. */
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
    private static final String ANSWER_DIGEST = "882576ba1e935cb3b84e8edd9957d4d59b36d31a4c49185192750cca730f1884";

    private static final int SAMPLE_SIZE = 524288;
    private static final int CLASSIFIED = 22768;

    /** The bundled ruleset carries no address rule, so protocol and port are the axes worth sweeping. */
    private static final List<String> PROTOCOLS = List.of("tcp", "udp", "sctp", "dccp");

    private static Tree tree;
    private static int ruleCount;
    private static int preprocessedCount;

    /**
     * Built once: uninstrumented this is a ~1 s build, but every surefire JVM carries the JaCoCo agent
     * and that turns it into 30-50 s. Both rows below share the one tree.
     */
    @BeforeAll
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    static void buildTheBundledTree() throws InterruptedException {
        final List<Rule> rules;
        try (var stream = BundledRulesetTreeIdentityTest.class.getResourceAsStream("/classification-rules.csv")) {
            rules = new CsvImporter().parse(stream, true);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the bundled ruleset", e);
        }

        final var preprocessed = new ArrayList<PreprocessedRule>();
        for (final var rule : rules) {
            final var p = PreprocessedRule.of(rule);
            preprocessed.add(p);
            if (rule.canBeReversed()) {
                // exactly what DefaultClassificationEngine.reload() feeds Tree.of
                preprocessed.add(p.reverse());
            }
        }

        ruleCount = rules.size();
        preprocessedCount = preprocessed.size();
        tree = Tree.of(preprocessed);
    }

    /**
     * The sweep, and the order the digest is taken in. Every port of every protocol the ruleset names,
     * once as the destination and once as the source, because the ruleset is reversed into the tree and
     * both directions must answer. Requests are handed over one at a time rather than collected: half a
     * million of them at once is heap the surefire JVM does not need to find.
     */
    private static void sweep(final Consumer<ClassificationRequest> consumer) {
        for (final var name : PROTOCOLS) {
            final Protocol protocol = Protocols.getProtocol(name);
            assertThat(protocol).as("the sweep must name protocols the ruleset uses: %s", name).isNotNull();
            for (var port = 0; port <= 65535; port++) {
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withSrcPort(0).withDstPort(port).build());
                consumer.accept(ClassificationRequest.builder()
                        .withProtocol(protocol).withSrcPort(port).withDstPort(0).build());
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void theBundledRulesetBuildsTheTreeItHasAlwaysBuilt() {
        // the ruleset this fingerprint is of - without this the pins below could be met by a
        // truncated or mis-parsed CSV
        assertThat(ruleCount).as("rules parsed from the bundled CSV").isEqualTo(6248);
        assertThat(preprocessedCount).as("preprocessed rules fed to Tree.of").isEqualTo(12496);

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

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void everyAnswerOverTheFullPortSweepIsTheAnswerItHasAlwaysBeen() throws NoSuchAlgorithmException {
        final var digest = MessageDigest.getInstance("SHA-256");
        final var index = new int[] {0};
        final var classified = new int[] {0};
        sweep(request -> {
            final var answer = tree.classify(request);
            if (answer != null) {
                classified[0]++;
            }
            digest.update((index[0]++ + "=" + answer + "\n").getBytes(StandardCharsets.UTF_8));
        });

        assertThat(index[0]).as("requests swept").isEqualTo(SAMPLE_SIZE);
        // a positive control on the digest: a tree that answered null to everything would still
        // produce a stable digest, and this is what says it did not
        assertThat(classified[0]).as("requests the tree gave a name to").isEqualTo(CLASSIFIED);
        assertThat(HexFormat.of().formatHex(digest.digest()))
                .as("SHA-256 over every answer of the sweep, in order")
                .isEqualTo(ANSWER_DIGEST);

        // and a readable first signal, so a digest failure is not the only thing a reader sees
        assertThat(tree.classify(ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("udp")).withDstPort(123).build())).isEqualTo("ntp");
        assertThat(tree.classify(ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol("tcp")).withDstPort(22).build())).isEqualTo("ssh");
    }
}
