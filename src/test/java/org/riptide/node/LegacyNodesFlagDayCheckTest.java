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
                "riptide.nodes.core\u2028router.subnet-address",
                // the terminator AT the boundary position, which the first fix still missed:
                // "nodes\ncore" is what Spring flattens a quoted YAML key into
                "riptide.nodes\ncore.subnet-address")) {
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
                "RIPTIDE_NODES_SERVICE_PORT_METRICS",
                "RIPTIDE_NODES_PORT",
                "RIPTIDE_NODES_PORT_8080_TCP_ADDR",
                "RIPTIDE_NODES_NAME",
                // a Service named riptide-nodes-headless: the platform generates these too,
                // and the first exemption crash-looped on them
                "RIPTIDE_NODES_HEADLESS_SERVICE_HOST",
                "RIPTIDE_NODES_METRICS_SERVICE_PORT",
                // {SVCNAME}_PORT is injected for EVERY Service, not just the unsuffixed name.
                // The previous exemption tested equality against RIPTIDE_NODES_PORT alone, so
                // any suffixed Service crash-looped every pod in the namespace
                "RIPTIDE_NODES_HEADLESS_PORT",
                "RIPTIDE_NODES_METRICS_PORT",
                "RIPTIDE_NODES_PORT_8080_UDP_ADDR",
                // Services whose NAME ends in a node-field word. The first fix wrote its field
                // alternatives as SNMP(_[A-Z0-9_]+)? and INTERFACES(_[A-Z0-9_]+)?, reproducing
                // inside the fix the very defect it fixed: [A-Z0-9_]+ spans '_' exactly as \\w
                // does, so these read as node fields and crash-looped. They were exempt BEFORE
                // that fix, so it was a regression, and no case here could see it
                "RIPTIDE_NODES_SNMP_SERVICE_HOST",
                "RIPTIDE_NODES_SNMP_SERVICE_PORT",
                "RIPTIDE_NODES_SNMP_PORT",
                "RIPTIDE_NODES_SNMP_PORT_161_UDP_ADDR",
                "RIPTIDE_NODES_INTERFACES_SERVICE_HOST",
                // a named Service port that collides with a node field word, but is not one
                "RIPTIDE_NODES_SERVICE_PORT_SNMP")) {
            assertThatCode(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                    sources("env", Map.of(link, "10.0.0.1"))))
                    .as("must not reject the service link %s", link)
                    .doesNotThrowAnyException();
        }
        // and the real environment forms are still caught alongside them — including nodes
        // whose names START like the platform suffixes, which the first exemption's
        // unanchored alternatives waved through silently
        for (final String legacy : List.of(
                "RIPTIDE_NODES_CORE_SUBNET_ADDRESS",
                "RIPTIDE_NODES_PORT_MIRROR_SUBNET_ADDRESS",
                "RIPTIDE_NODES_NAME_SERVER_SUBNET_ADDRESS",
                "RIPTIDE_NODES_ENV_A_SUBNET_ADDRESS",
                // node names that end in a platform suffix, with a real field after them. The
                // previous exemption's (_\w+)? tail spanned '_', so it absorbed the field name
                // and waved these through silently — the same defect as port-mirror above, in
                // the alternative that was added to fix port-mirror
                "RIPTIDE_NODES_EDGE_SERVICE_PORT_SUBNET_ADDRESS",
                "RIPTIDE_NODES_MGMT_SERVICE_PORT_SNMP_COMMUNITY",
                "RIPTIDE_NODES_SVC_SERVICE_PORT_INTERFACES_1_NAME",
                "RIPTIDE_NODES_PORT_1_TCP_SUBNET_ADDRESS",
                "RIPTIDE_NODES_A_SERVICE_HOST_OBSERVATION_DOMAIN",
                // the relaxed-binding spellings 0.8 accepted. Matching raw text caught only the
                // kebab-case one; normalising the name folds all three onto the same comparison
                "RIPTIDE_NODES_EDGE_SERVICE_PORT_SUBNETADDRESS",
                "RIPTIDE_NODES_CORE_SNMPVERSION",
                "RIPTIDE_NODES_CORE_OBSERVATIONDOMAIN",
                // a node genuinely named 'snmp' carries a name segment; the bare
                // RIPTIDE_NODES_SNMP_PORT above does not, and no legacy key has that shape
                "RIPTIDE_NODES_SNMP_SNMP_PORT")) {
            assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                    sources("env", Map.of(legacy, "10.0.0.1"))))
                    .as("must catch the legacy node behind %s", legacy)
                    .isInstanceOf(IllegalStateException.class);
        }
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

    /**
     * The remedy depends on where the tree lives. The converter reads a file, so an environment-only
     * deployment cannot act on an instruction naming {@code <your-config.yaml>} (#614).
     */
    @Test
    void anEnvironmentSourcedTreeGetsEnvironmentSpecificInstructions() {
        final MutablePropertySources env = new MutablePropertySources();
        env.addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS", "10.0.0.1")));

        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(env))
                .hasMessageContaining("process environment")
                .hasMessageContaining("was never bound")
                .hasMessageContaining("Remove the variable")
                // it must NOT hand this deployment a file-based invocation as the primary remedy
                .hasMessageNotContaining("riptide convert <your-config.yaml>");
    }

    /** A file-sourced tree is unchanged: same message as before the branch existed. */
    @Test
    void aFileSourcedTreeKeepsTheConverterInstruction() {
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptide.nodes.core-router.subnet-address", "10.0.0.1"))))
                .hasMessageContaining("riptide convert <your-config.yaml>")
                .hasMessageNotContaining("process environment");
    }

    /**
     * The claim the environment message makes, pinned against Spring rather than restated.
     *
     * <p>That message tells operators a multi-word node name was never active in 0.8. That is a
     * statement about how Spring binds a {@code Map<String, NodeDefinition>} from environment
     * variables, and it is load-bearing: if it is wrong, riptide is telling people to delete live
     * configuration. Measured here against the shipped Spring, so a version bump that changes the
     * behaviour fails this test rather than quietly making the message false.</p>
     *
     * <p>The 0.8 {@code NodeDefinition} is gone, so a stand-in with the same shape is bound: the
     * behaviour under test is Spring's map-key splitting, not that class.</p>
     */
    @Test
    void springNeverBoundAMultiWordNodeNameFromTheEnvironment() {
        assertThat(bindNodes("RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS"))
                .as("a hyphenated name reaches no node at all, so it was not active in 0.8")
                .isEmpty();

        assertThat(bindNodes("RIPTIDE_NODES_EDGE_SUBNET_ADDRESS"))
                .as("a single-segment name does bind, which is why the message keeps a path for it")
                .containsOnlyKeys("edge");
    }

    /** Binds one environment variable as 0.8 bound its nodes map, and returns the node keys. */
    private static Map<String, StandInNode> bindNodes(final String variable) {
        final var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().replace("systemEnvironment",
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(variable, "10.0.0.1")));
        return org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("riptide.nodes",
                        org.springframework.boot.context.properties.bind.Bindable
                                .mapOf(String.class, StandInNode.class))
                .orElse(Map.of());
    }

    /** Same shape as the deleted 0.8 NodeDefinition, for the binding probe above. */
    public static class StandInNode {
        private String subnetAddress;

        public String getSubnetAddress() {
            return this.subnetAddress;
        }

        public void setSubnetAddress(final String subnetAddress) {
            this.subnetAddress = subnetAddress;
        }
    }

    @Test
    void aCamelCaseSpellingIsCaught() {
        assertThat(sources("file", Map.of("riptideNodes.core.subnetAddress", "10.0.0.1"))).isNotNull();
        assertThatThrownBy(() -> LegacyNodesFlagDayCheck.failOnLegacyNodes(
                sources("file", Map.of("riptideNodes.core.subnetAddress", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
