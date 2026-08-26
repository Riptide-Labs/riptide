/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One failure naming everything, rather than one key per boot.
 *
 * <p>The defect these pin is not that any single check was wrong. Each reported its own first match
 * and threw, and the three were separate beans with no ordering, so a configuration carrying six
 * offending keys cost six edit-and-restart cycles in an order the operator could not predict
 * (#562).</p>
 */
class ObsoleteKeysTest {

    private static MutablePropertySources file(final Map<String, Object> properties) {
        final MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("file", properties));
        return sources;
    }

    @Test
    void aCleanConfigurationPasses() {
        assertThatCode(() -> ObsoleteKeys.failOnObsoleteKeys(
                file(Map.of("riptide.snmp.polling.default.refresh-interval", "PT2M"))))
                .doesNotThrowAnyException();
    }

    /**
     * The test that would have made #562 unnecessary.
     *
     * <p>Every category at once, in one failure. Asserting each category's own remediation rather
     * than only that a failure happened: the collected message names them all, so "it threw" is now
     * satisfied by any single one of them and would stay green with two categories dropped.</p>
     */
    @Test
    void keysFromEveryCategoryAreReportedTogether() {
        final Map<String, Object> everything = new HashMap<>();
        everything.put("riptide.nodes.core-router.subnet-address", "10.0.0.1");
        everything.put("riptide.nodes.edge.subnet-address", "10.0.1.1");
        everything.put("riptide.snmp.poll.refresh-interval-ms", "120000");
        everything.put("riptide.snmp.agents.10-0-0-0-24.credentials", "corp");

        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(file(everything)))
                .isInstanceOf(IllegalStateException.class)
                // the count is over keys, not categories: four keys in three categories
                .hasMessageContaining("carries 4 key(s) this release does not read")
                // every offending key named, so no second boot is needed to discover one
                .hasMessageContaining("riptide.nodes.core-router.subnet-address")
                .hasMessageContaining("riptide.nodes.edge.subnet-address")
                .hasMessageContaining("riptide.snmp.poll.refresh-interval-ms")
                .hasMessageContaining("riptide.snmp.agents.10-0-0-0-24.credentials")
                // and each category's own remediation, which is the part worth reading
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageContaining("riptide.snmp.polling.<name>.refresh-interval")
                .hasMessageContaining("named by riptide.inventory.file");
    }

    /**
     * The opening line has to be true of every category it lists.
     *
     * <p>"Keys that 0.9 removed" would be false here: {@code riptide.snmp.agents} is a current key
     * in the wrong file, not a removed one. Naming the set after the narrower property is how
     * {@code upgrading-from-0.8.md} came to omit every category that was not retired.</p>
     */
    @Test
    void theOpeningLineDoesNotClaimTheKeysWereRemoved() {
        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(
                file(Map.of("riptide.snmp.agents.10-0-0-0-24.credentials", "corp"))))
                .hasMessageContaining("this release does not read")
                .hasMessageNotContaining("removed");
    }

    /**
     * Bounded, and the overflow count asserted directly.
     *
     * <p>A generated configuration can carry thousands. The count is asserted rather than the
     * presence of an "and N more" phrase, because an off-by-one there reads as correct and no
     * {@code contains} check can tell.</p>
     */
    @Test
    void aLongListIsBoundedAndTheRemainderCounted() {
        final Map<String, Object> many = IntStream.range(0, 26).boxed().collect(Collectors.toMap(
                i -> "riptide.nodes.node-%d.subnet-address".formatted(i),
                i -> "10.0.0.%d".formatted(i)));

        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(file(many)))
                .hasMessageContaining("carries 26 key(s) this release does not read")
                .hasMessageContaining("and 6 further key(s) listed no further");
    }

    /**
     * A mixed stack gets both remediations, and each only because a key of that kind matched.
     *
     * <p>The converter reads a file, so the file instruction is unactionable for an
     * environment-configured deployment and the environment instruction is noise for a file-based
     * one (#614). A flattened walk would produce the right message for either stack alone and the
     * wrong one here, which is why the source is carried through the collector.</p>
     */
    @Test
    void aMixedStackGetsTheRemediationForEachSourceKind() {
        final MutablePropertySources mixed = new MutablePropertySources();
        mixed.addFirst(new MapPropertySource("file",
                Map.of("riptide.nodes.edge.subnet-address", "10.0.0.1")));
        mixed.addLast(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("RIPTIDE_NODES_CORE_SUBNET_ADDRESS", "10.0.1.1")));

        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(mixed))
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageContaining("in the process environment");
    }

    /**
     * The same mixed stack with the environment source first.
     *
     * <p>Not redundant with the case above, and a mutation proved it: replacing "any match from a
     * file" with "the first match's kind" left that test green, because it happens to put the file
     * source first. Only the reversed order distinguishes a per-group answer from a whole-group one.
     * Both orderings are realistic — a container reads its environment ahead of a mounted file.</p>
     */
    @Test
    void aMixedStackIsNotDecidedByWhichSourceComesFirst() {
        final MutablePropertySources environmentFirst = new MutablePropertySources();
        environmentFirst.addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("RIPTIDE_NODES_CORE_SUBNET_ADDRESS", "10.0.1.1")));
        environmentFirst.addLast(new MapPropertySource("file",
                Map.of("riptide.nodes.edge.subnet-address", "10.0.0.1")));

        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(environmentFirst))
                .as("the file remediation must survive an environment source being found first")
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageContaining("in the process environment");
    }

    /** A file-only stack must not be handed the environment paragraph it cannot act on. */
    @Test
    void aFileOnlyStackGetsOnlyTheFileRemediation() {
        assertThatThrownBy(() -> ObsoleteKeys.failOnObsoleteKeys(
                file(Map.of("riptide.nodes.edge.subnet-address", "10.0.0.1"))))
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageNotContaining("in the process environment");
    }

    /** Each category counts its own keys, so a group's number cannot drift from its list. */
    @Test
    void eachCategoryReportsItsOwnCount() {
        final Map<String, Object> twoAndOne = new HashMap<>();
        twoAndOne.put("riptide.nodes.a.subnet-address", "10.0.0.1");
        twoAndOne.put("riptide.nodes.b.subnet-address", "10.0.0.2");
        twoAndOne.put("riptide.snmp.poll.snapshot-expiry-ms", "600000");

        assertThat(catchMessage(twoAndOne))
                .contains("riptide.nodes tree (2)")
                .contains("retired per-agent poll keys (1)");
    }

    private static String catchMessage(final Map<String, Object> properties) {
        try {
            ObsoleteKeys.failOnObsoleteKeys(file(properties));
            throw new AssertionError("expected a failure");
        } catch (final IllegalStateException expected) {
            return expected.getMessage();
        }
    }
}
