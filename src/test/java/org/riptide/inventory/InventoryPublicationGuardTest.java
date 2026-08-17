/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.secrets.SecretRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The torn-write guard (#535): a tree going populated to empty is refused unless the
 * source wrote it as an explicit empty mapping. Truncation cannot forge the marker — a
 * torn write is missing a tree, it never contains one replaced with a literal {@code {}}.
 */
class InventoryPublicationGuardTest {

    private static final SnmpProfilesConfig PROFILES = new SnmpProfilesConfig(
            Map.of("corp", CredentialSet.community(CredentialVersion.V2C, SecretRef.of("public"))),
            Map.of());

    private static final String BOTH_TREES = """
            riptide:
              snmp:
                agents:
                  "10.0.0.1":
                    credentials: corp
              exporters:
                core:
                  address: 10.0.0.1
            """;

    private static InventorySnapshot parse(final String yaml) {
        return InventoryLoader.parse(PROFILES, yaml, "guard.yaml");
    }

    private static Inventory serving(final String yaml) {
        final Inventory inventory = new Inventory(PROFILES, new InventoryConfig());
        inventory.swap(parse(yaml));
        return inventory;
    }

    @Test
    void anAbsentTreeOverAPopulatedOneIsRefused() {
        // both loss directions: which tree a torn read is missing depends only on the
        // order the writer flushed them in, and both orders exist in the wild
        record Torn(String label, String yaml) {
        }
        for (final Torn torn : new Torn[] {
                new Torn("agents flushed, exporters truncated", """
                        riptide:
                          snmp:
                            agents:
                              "10.0.0.1":
                                credentials: corp
                        """),
                new Torn("exporters flushed, agents truncated", """
                        riptide:
                          exporters:
                            core:
                              address: 10.0.0.1
                        """),
                // a tear dies at a bare key; the literal `riptide: {}` is authored and
                // is now the sanctioned whole-empty spelling, tested separately
                new Torn("both truncated", "riptide:\n")}) {
            assertThatThrownBy(() -> serving(BOTH_TREES).swap(parse(torn.yaml())))
                    .as(torn.label())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("drops a whole tree")
                    .hasMessageContaining("agents: {}");
        }
    }

    @Test
    void anExplicitlyEmptyTreeIsADeliberateDecommission() {
        // the marker distinguishes "deliberately none" from "torn": each direction commits
        // when the vanishing tree is written as an explicit empty mapping
        final Inventory inventory = serving(BOTH_TREES);
        inventory.swap(parse("""
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    core:
                      address: 10.0.0.1
                """));
        assertThat(inventory.snapshot().agentCount()).isZero();
        assertThat(inventory.snapshot().exporterCount()).isEqualTo(1);

        inventory.swap(parse("""
                riptide:
                  snmp:
                    agents: {}
                  exporters: {}
                """));
        assertThat(inventory.snapshot().isEmpty()).isTrue();
    }

    /** A bare key with no value is not the marker: a write can tear exactly on the key line. */
    @Test
    void aBareKeyWithNoValueDoesNotForgeTheMarker() {
        assertThatThrownBy(() -> serving(BOTH_TREES).swap(parse("""
                riptide:
                  snmp:
                    agents:
                  exporters:
                    core:
                      address: 10.0.0.1
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drops a whole tree");
    }

    @Test
    void emptyOverEmptyStaysLegalSoBootIsUnchanged() {
        final Inventory inventory = new Inventory(PROFILES, new InventoryConfig());
        assertThatCode(() -> inventory.swap(InventorySnapshot.empty())).doesNotThrowAnyException();
        assertThatCode(() -> inventory.swap(parse("riptide: {}\n"))).doesNotThrowAnyException();
    }

    @Test
    void shrinkingWithoutVanishingStaysLegal() {
        final Inventory inventory = serving("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.1":
                        credentials: corp
                      "10.0.0.2":
                        credentials: corp
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        assertThatCode(() -> inventory.swap(parse(BOTH_TREES))).doesNotThrowAnyException();
        assertThat(inventory.snapshot().agentCount()).isEqualTo(1);
    }

    /** The same rule on the rebuild path keeps its null contract: refused, not thrown. */
    @Test
    void rebuildRefusesATornFileAndPublishesTheMarkedOne(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("inventory.yaml");
        final Inventory inventory = serving(BOTH_TREES);

        Files.writeString(file, """
                riptide:
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        assertThat(inventory.rebuildAndSwap(PROFILES, file)).isNull();
        assertThat(inventory.snapshot().agentCount()).isEqualTo(1);

        Files.writeString(file, """
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        assertThat(inventory.rebuildAndSwap(PROFILES, file)).isNotNull();
        assertThat(inventory.snapshot().agentCount()).isZero();
    }

    /**
     * The monitor-held check in the CAS path closes the caller's read-then-commit window
     * by DEFERRING, not throwing: false is what the caller already handles by re-parsing
     * next cycle against whatever is serving by then. The first version threw, which
     * landed in the watcher's failure path with the attempted hash committed — wedging
     * retries of that content until the file changed again.
     */
    @Test
    void theCasPathDefersARegressiveCandidateInsteadOfPublishingOrThrowing() {
        final Inventory inventory = serving(BOTH_TREES);
        // "---" parses to nothing with no marker anywhere: regressive over both trees
        assertThat(inventory.swapIfProfilesUnchanged(PROFILES, parse("riptide:\n"))).isFalse();
        assertThat(inventory.snapshot().agentCount()).isEqualTo(1);
        // and a marked candidate still publishes through the same path
        assertThat(inventory.swapIfProfilesUnchanged(PROFILES, parse("riptide: {}\n"))).isTrue();
        assertThat(inventory.snapshot().isEmpty()).isTrue();
    }

    /**
     * The marker is honoured at any ancestor, because the tear argument is the
     * ancestor's too: truncation dies at a bare or missing key, and an explicit empty
     * mapping at any level can only be authored.
     */
    @Test
    void anExplicitlyEmptyAncestorDeclaresTheTreesBelowIt() {
        // snmp: {} declares agents-empty while exporters survive
        final Inventory viaSnmp = serving(BOTH_TREES);
        viaSnmp.swap(parse("""
                riptide:
                  snmp: {}
                  exporters:
                    core:
                      address: 10.0.0.1
                """));
        assertThat(viaSnmp.snapshot().agentCount()).isZero();

        // riptide: {} declares both: the authored spelling of a whole empty inventory
        final Inventory viaRoot = serving(BOTH_TREES);
        viaRoot.swap(parse("riptide: {}\n"));
        assertThat(viaRoot.snapshot().isEmpty()).isTrue();

        // but the bare-key spellings of the same levels stay refused: a tear produces those
        for (final String torn : new String[] {"riptide:\n", "riptide:\n  snmp:\n"}) {
            assertThatThrownBy(() -> serving(BOTH_TREES).swap(parse(torn)))
                    .as("bare key: %s", torn.replace("\n", "\\n"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
