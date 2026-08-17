/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 0.9 flag day. Driven through real {@code PropertySource} implementations rather than a
 * stub, because the whole risk here is which spellings actually reach Spring: a check that
 * passes against a canned list of dotted keys and waves through an environment-configured
 * container would be worse than no check, since the operator would read a clean startup as
 * confirmation their configuration is live.
 */
class LegacyNodesFlagDayCheckTest {

    private static MutablePropertySources sources(final String name, final Map<String, Object> properties) {
        final MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource(name, properties));
        return sources;
    }

    @Test
    void theNameKeyedMapFailsStartup() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes.core-router.subnet-address", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.nodes.core-router.subnet-address");
    }

    /** The shape the retired indexed-list check guarded; now one case of the general rule. */
    @Test
    void theIndexedListFailsStartup() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes[0].subnet-address", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.nodes[0]");
    }

    /**
     * The form most likely to be carrying a legacy tree, and the one a check written against
     * the dotted spelling alone would miss entirely.
     */
    @Test
    void theEnvironmentVariableFormFailsStartup() {
        final MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("env",
                Map.of("RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS", "10.0.0.1")));

        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(sources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS");
    }

    /** An operator who reads only the error has to be able to act on it. */
    @Test
    void theErrorNamesTheConverterInvocationAndTheReleaseNotes() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes.core.subnet-address", "10.0.0.1"))))
                // the exact invocation, not just the word "convert", which appears in prose
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageContaining("--out-inventory")
                .hasMessageContaining("release notes");
        // NOTE: story 3.3 writes that section. It does not exist in the repo yet, and this
        // error ships in the same release, so the reference resolves at 0.9 and not before.
    }

    /**
     * The 0.9 surfaces must not trip it. A sloppier match on "nodes" would take the whole
     * configuration down on a key that is entirely correct.
     */
    @Test
    void theCurrentConfigurationSurfacesAreUntouched() {
        for (final String live : List.of(
                "riptide.inventory.file",
                "riptide.snmp.credentials.corp-v3.version",
                "riptide.snmp.polling.slow.refresh-interval",
                "riptide.exporters.core-router.address",
                "riptide.routing.prefixes",
                // the over-match this check had to be tightened for: a key about a node, not
                // about the removed nodes tree, and a Kubernetes service link for a service
                // named riptide-node
                "riptide.node.selector",
                "riptide.node-scan.enabled",
                "RIPTIDE_NODE_SERVICE_HOST",
                "RIPTIDE_NODE_SERVICE_PORT",
                "riptide.nodesource.url",
                "riptide.clickhouse.url",
                "RIPTIDE_INVENTORY_FILE")) {
            assertThatCode(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                    sources("live", Map.of(live, "x"))))
                    .as("must not reject %s", live)
                    .doesNotThrowAnyException();
        }
    }

    /** The indexed form cannot be fed to the converter, so it keeps its own instruction. */
    @Test
    void theIndexedFormKeepsTheSpecificRewriteInstruction() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes[0].subnet-address", "10.0.0.1"))))
                .hasMessageContaining("name-keyed map");
        // and a name-keyed tree does not carry that hint, because it converts directly
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes.core.subnet-address", "10.0.0.1"))))
                .hasMessageNotContaining("name-keyed map");
    }

    /**
     * The boundary check used {@code matches()} with a trailing {@code .*}, which cannot cross
     * a line terminator, so a key carrying a newline after "nodes" passed straight through and
     * the operator kept a silently inert device configuration. Both a quoted YAML key and a
     * {@code .properties} line can produce exactly this name.
     */
    @Test
    void aKeyCarryingALineTerminatorStillFails() {
        for (final String key : List.of(
                "riptide.nodes.core\nrouter.subnet-address",
                "riptide.nodes\n",
                "riptide.nodes.core\u2028router.subnet-address")) {
            assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                    sources("file", Map.of(key, "10.0.0.1"))))
                    .as("must reject %s", key.replace("\n", "\\n"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * A Service named riptide-nodes makes the platform inject these; the operator never wrote
     * them, and failing on them takes a pod down with no in-namespace remedy but a rename.
     */
    @Test
    void containerServiceLinksAreNotConfiguration() {
        for (final String link : List.of(
                "RIPTIDE_NODES_SERVICE_HOST",
                "RIPTIDE_NODES_SERVICE_PORT",
                "RIPTIDE_NODES_PORT",
                "RIPTIDE_NODES_PORT_8080_TCP_ADDR",
                "RIPTIDE_NODES_NAME",
                "RIPTIDE_NODES_ENV_PATH")) {
            assertThatCode(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                    sources("env", Map.of(link, "10.0.0.1"))))
                    .as("must not reject the service link %s", link)
                    .doesNotThrowAnyException();
        }
        // and the real environment form is still caught alongside them
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("env", Map.of("RIPTIDE_NODES_CORE_SUBNET_ADDRESS", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A %n inside a %s substitution is never processed, so the hint printed literal "%n". */
    @Test
    void theIndexedHintIsFormattedNotPrintedLiterally() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes[0].subnet-address", "10.0.0.1"))))
                .hasMessageNotContaining("%n")
                .hasMessageContaining("name-keyed map");
    }

    @Test
    void anEmptySourceStackIsFine() {
        assertThatCode(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(new MutablePropertySources()))
                .doesNotThrowAnyException();
    }

    /** The message has to name which key, or a large configuration is a search problem. */
    @Test
    void theOffendingKeyIsNamed() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes.the-one-that-is-wrong.subnet-address", "10.0.0.1"))))
                .hasMessageContaining("the-one-that-is-wrong");
    }

    @Test
    void aCamelCaseSpellingIsCaught() {
        assertThat(sources("file", Map.of("riptideNodes.core.subnetAddress", "10.0.0.1"))).isNotNull();
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptideNodes.core.subnetAddress", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
