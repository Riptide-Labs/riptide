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

public class CsvImporterTest {

    private static final String HEADER = "name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional\n";

    private static List<Rule> parse(final String csv) throws IOException {
        return new CsvImporter().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), true);
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

    /**
     * Regression: a client ephemeral port that collides with another rule's registered port
     * (e.g. an https connection whose client side happens to be 8881, galaxy4d's port) matches
     * two omnidirectional rules with the same aspect count. The earlier CSV row must win — in
     * both flow directions — instead of the winner depending on decision-tree internals.
     */
    @Test
    void verifyEarlierRuleWinsEphemeralPortCollision() throws IOException, InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> {
            try {
                return parse(HEADER
                        + "https;tcp;;;;443;;true\n"
                        + "galaxy4d;tcp;;;;8881;;true\n");
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        // client -> server: dstPort matches https directly, srcPort matches galaxy4d reversed
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(8881).withDstPort(443).build()))
                .isEqualTo("https");
        // server -> client: srcPort matches https reversed, dstPort matches galaxy4d directly
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(443).withDstPort(8881).build()))
                .isEqualTo("https");

        // and the order is what decides: with the rows swapped, galaxy4d wins both directions
        final var swapped = new DefaultClassificationEngine(() -> {
            try {
                return parse(HEADER
                        + "galaxy4d;tcp;;;;8881;;true\n"
                        + "https;tcp;;;;443;;true\n");
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(swapped.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(8881).withDstPort(443).build()))
                .isEqualTo("galaxy4d");
        assertThat(swapped.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(443).withDstPort(8881).build()))
                .isEqualTo("galaxy4d");
    }
}
