/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.csv;

import org.junit.jupiter.api.Test;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ProtocolType;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.DefaultClassificationEngine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CsvImporterTest {

    private static final String HEADER = "name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional\n";

    private static List<Rule> parse(final String csv) throws IOException {
        return new CsvImporter().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), true);
    }

    private static DefaultClassificationEngine engineFor(final String rows) throws IOException, InterruptedException {
        final var rules = parse(HEADER + rows);
        return new DefaultClassificationEngine(() -> rules);
    }

    private static String classify(final DefaultClassificationEngine engine, final int srcPort, final int dstPort) {
        return engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(srcPort).withDstPort(dstPort).build());
    }

    @Test
    void verifyPositionsFollowRowOrder() throws IOException {
        final var rules = parse(HEADER
                + "ntp;udp;;;;123;;true\n"
                + "ssh;tcp;;;;22;;true\n"
                + "https;tcp;;;;443;;true\n");

        assertThat(rules).extracting(Rule::getName).containsExactly("ntp", "ssh", "https");
        assertThat(rules).extracting(Rule::getPosition).containsExactly(0, 1, 2);
    }

    @Test
    void verifyMissingHeaderFailsFast() {
        // Without the header line the first rule would be consumed as a pseudo-header,
        // silently dropping it and shifting every position by one. Both shapes must fail:
        // a first row with empty fields (rejected by commons-csv header parsing) ...
        assertThatThrownBy(() -> parse("ntp;udp;;;;123;;true\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("name;protocol");
        // ... and a fully-populated first row, which commons-csv would happily consume.
        assertThatThrownBy(() -> parse("ntp;udp;10.0.0.1;123;10.0.0.2;123;e1;true\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("name;protocol");
    }

    /**
     * Regression: a client ephemeral port that collides with another rule's registered port
     * (e.g. an https connection whose client side happens to be 8881, galaxy4d's port) matches
     * two omnidirectional rules with the same aspect count. The earlier CSV row must win — in
     * both flow directions — instead of the winner depending on decision-tree internals.
     */
    @Test
    void verifyEarlierRuleWinsEphemeralPortCollision() throws IOException, InterruptedException {
        final var engine = engineFor(
                "https;tcp;;;;443;;true\n"
                + "galaxy4d;tcp;;;;8881;;true\n");

        // client -> server: dstPort matches https directly, srcPort matches galaxy4d reversed
        assertThat(classify(engine, 8881, 443)).isEqualTo("https");
        // server -> client: srcPort matches https reversed, dstPort matches galaxy4d directly
        assertThat(classify(engine, 443, 8881)).isEqualTo("https");

        // and the order is what decides: with the rows swapped, galaxy4d wins both directions
        final var swapped = engineFor(
                "galaxy4d;tcp;;;;8881;;true\n"
                + "https;tcp;;;;443;;true\n");
        assertThat(classify(swapped, 8881, 443)).isEqualTo("galaxy4d");
        assertThat(classify(swapped, 443, 8881)).isEqualTo("galaxy4d");
    }

    /**
     * Row order is the evaluation priority, so the bundled ruleset's ordering is load-bearing:
     * a broad rule placed before a more specific one shadows it. Guard the invariant the
     * bundled file relies on — every rule constraining fewer than two of the classifier
     * aspects (protocol, ports, addresses) stays at the very end, where it cannot shadow.
     */
    @Test
    void verifyBundledRulesetKeepsBroadRulesLast() throws IOException {
        final List<Rule> rules;
        try (var stream = CsvImporterTest.class.getResourceAsStream("/classification-rules.csv")) {
            rules = new CsvImporter().parse(stream, true);
        }
        assertThat(rules).hasSizeGreaterThan(6000);

        int firstBroad = -1;
        for (int i = 0; i < rules.size(); i++) {
            final var rule = rules.get(i);
            final var aspects = (rule.hasProtocolDefinition() ? 1 : 0)
                    + (rule.hasSrcPortDefinition() ? 1 : 0)
                    + (rule.hasDstPortDefinition() ? 1 : 0)
                    + (rule.hasSrcAddressDefinition() ? 1 : 0)
                    + (rule.hasDstAddressDefinition() ? 1 : 0);
            if (aspects < 2) {
                if (firstBroad == -1) {
                    firstBroad = i;
                }
            } else if (firstBroad != -1) {
                assertThat(i)
                        .as("rule '%s' (row %s) is more specific than the broad rule at row %s before it"
                                + " — broad rules must stay last or they shadow later rules",
                                rule.getName(), i, firstBroad)
                        .isLessThan(firstBroad);
            }
        }
    }
}
