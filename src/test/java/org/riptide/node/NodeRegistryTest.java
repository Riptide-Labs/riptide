/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.secrets.SecretRef;
import org.riptide.snmp.SnmpDefinition;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NodeRegistryTest {

    private static NodeDefinition node(final String subnet, final Long observationDomain, final String community) {
        final NodeDefinition node = new NodeDefinition();
        node.setSubnetAddress(new IPAddressString(subnet));
        node.setObservationDomain(observationDomain);
        if (community != null) {
            final SnmpDefinition snmp = new SnmpDefinition();
            snmp.setCommunity(SecretRef.of(community));
            node.setSnmp(snmp);
        }
        return node;
    }

    private static ExporterIdentity identity(final String host, final long observationDomain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), observationDomain);
    }

    @SafeVarargs
    private static NodeRegistry registry(final Map.Entry<String, NodeDefinition>... nodes) {
        final Map<String, NodeDefinition> map = new LinkedHashMap<>();
        for (final var entry : nodes) {
            map.put(entry.getKey(), entry.getValue());
        }
        final NodeRegistry registry = new NodeRegistry();
        registry.setNodes(map);
        registry.validate();
        return registry;
    }

    @Test
    public void observationDomainPinnedNodeWinsOverWildcard() throws Exception {
        final NodeRegistry registry = registry(
                Map.entry("wildcard", node("10.0.0.0/24", null, "wildcard")),
                Map.entry("pinned", node("10.0.0.0/24", 42L, "pinned")));

        assertThat(registry.lookup(identity("10.0.0.1", 42))).map(Node::label).hasValue("pinned");
        assertThat(registry.lookup(identity("10.0.0.1", 7))).map(Node::label).hasValue("wildcard");
    }

    @Test
    public void longestPrefixWinsInEitherDeclarationOrder() throws Exception {
        final NodeRegistry coarseFirst = registry(
                Map.entry("coarse", node("10.20.0.0/16", null, "a")),
                Map.entry("fine", node("10.20.30.0/24", null, "b")));
        final NodeRegistry fineFirst = registry(
                Map.entry("fine", node("10.20.30.0/24", null, "b")),
                Map.entry("coarse", node("10.20.0.0/16", null, "a")));

        assertThat(coarseFirst.lookup(identity("10.20.30.5", 0))).map(Node::label).hasValue("fine");
        assertThat(fineFirst.lookup(identity("10.20.30.5", 0))).map(Node::label).hasValue("fine");
        // outside the /24, the /16 still matches
        assertThat(coarseFirst.lookup(identity("10.20.99.5", 0))).map(Node::label).hasValue("coarse");
    }

    @Test
    public void bareHostAddressIsMostSpecific() throws Exception {
        final NodeRegistry registry = registry(
                Map.entry("subnet", node("10.0.0.0/24", null, "a")),
                Map.entry("host", node("10.0.0.7", null, "b")));

        assertThat(registry.lookup(identity("10.0.0.7", 0))).map(Node::label).hasValue("host");
        assertThat(registry.lookup(identity("10.0.0.8", 0))).map(Node::label).hasValue("subnet");
    }

    @Test
    public void pinnedOnlyRegistryDoesNotMatchOtherDomains() throws Exception {
        final NodeRegistry registry = registry(Map.entry("pinned", node("10.0.0.0/24", 42L, "pinned")));

        assertThat(registry.lookup(identity("10.0.0.1", 42))).isPresent();
        assertThat(registry.lookup(identity("10.0.0.1", 7))).isEmpty();
    }

    @Test
    public void subnetMissYieldsEmpty() throws Exception {
        final NodeRegistry registry = registry(Map.entry("wildcard", node("10.0.0.0/24", null, "wildcard")));

        assertThat(registry.lookup(identity("192.168.1.1", 0))).isEmpty();
    }

    @Test
    public void trueTieFailsValidation() {
        assertThatThrownBy(() -> registry(
                Map.entry("site-a", node("10.20.30.0/24", null, "a")),
                Map.entry("backup", node("10.20.30.0/24", null, "b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site-a")
                .hasMessageContaining("backup");
    }

    @Test
    public void samePinnedDomainSameSubnetFailsValidation() {
        assertThatThrownBy(() -> registry(
                Map.entry("one", node("10.20.30.0/24", 42L, "a")),
                Map.entry("two", node("10.20.30.0/24", 42L, "b"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void sameSubnetDifferentPinningIsNotATie() throws Exception {
        final NodeRegistry registry = registry(
                Map.entry("pinned", node("10.20.30.0/24", 42L, "a")),
                Map.entry("wildcard", node("10.20.30.0/24", null, "b")),
                Map.entry("other-domain", node("10.20.30.0/24", 7L, "c")));

        assertThat(registry.lookup(identity("10.20.30.5", 42))).map(Node::label).hasValue("pinned");
        assertThat(registry.lookup(identity("10.20.30.5", 7))).map(Node::label).hasValue("other-domain");
        assertThat(registry.lookup(identity("10.20.30.5", 1))).map(Node::label).hasValue("wildcard");
    }

    @Test
    public void nodeWithoutSnmpConfigHasNoEndpoint() throws Exception {
        final NodeRegistry registry = registry(Map.entry("bare", node("10.0.0.0/24", null, null)));

        final var match = registry.lookup(identity("10.0.0.1", 0));
        assertThat(match).isPresent();
        assertThat(match.get().snmpEndpoint()).isEmpty();
    }

    @Test
    public void sflowMatchesByAgentAddress() throws Exception {
        final NodeRegistry registry = registry(
                Map.entry("switch-a", node("10.1.0.0/16", null, "a")));

        final var identity = new ExporterIdentity.Sflow(InetAddress.getByName("10.1.1.1"), 0);

        assertThat(registry.lookup(identity)).map(Node::label).hasValue("switch-a");
    }

    @Test
    public void sflowSubAgentPinsViaObservationDomainKey() throws Exception {
        final NodeRegistry registry = registry(
                Map.entry("wildcard", node("10.1.0.0/16", null, "w")),
                Map.entry("pinned", node("10.1.0.0/16", 7L, "p")));

        assertThat(registry.lookup(new ExporterIdentity.Sflow(InetAddress.getByName("10.1.1.1"), 7)))
                .map(Node::label).hasValue("pinned");
        assertThat(registry.lookup(new ExporterIdentity.Sflow(InetAddress.getByName("10.1.1.1"), 3)))
                .map(Node::label).hasValue("wildcard");
    }
}
