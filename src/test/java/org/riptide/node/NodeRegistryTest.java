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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class NodeRegistryTest {

    private static NodeDefinition node(final String label, final String subnet, final Long observationDomain, final String community) {
        final NodeDefinition node = new NodeDefinition();
        node.setLabel(label);
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

    private static NodeRegistry registry(final NodeDefinition... nodes) {
        final NodeRegistry registry = new NodeRegistry();
        registry.setNodes(List.of(nodes));
        return registry;
    }

    @Test
    public void observationDomainPinnedNodeWinsOverWildcard() throws Exception {
        final NodeRegistry registry = registry(
                node("wildcard", "10.0.0.0/24", null, "wildcard"),
                node("pinned", "10.0.0.0/24", 42L, "pinned"));

        assertThat(registry.lookup(identity("10.0.0.1", 42))).map(Node::label).hasValue("pinned");
        assertThat(registry.lookup(identity("10.0.0.1", 7))).map(Node::label).hasValue("wildcard");
    }

    @Test
    public void pinnedOnlyRegistryDoesNotMatchOtherDomains() throws Exception {
        final NodeRegistry registry = registry(node("pinned", "10.0.0.0/24", 42L, "pinned"));

        assertThat(registry.lookup(identity("10.0.0.1", 42))).isPresent();
        assertThat(registry.lookup(identity("10.0.0.1", 7))).isEmpty();
    }

    @Test
    public void subnetMissYieldsEmpty() throws Exception {
        final NodeRegistry registry = registry(node("wildcard", "10.0.0.0/24", null, "wildcard"));

        assertThat(registry.lookup(identity("192.168.1.1", 0))).isEmpty();
    }

    @Test
    public void firstSubnetMatchOrderIsKeptWithinAGroup() throws Exception {
        final NodeRegistry registry = registry(
                node("first", "10.0.0.0/16", null, "first"),
                node("second", "10.0.0.0/24", null, "second"));

        assertThat(registry.lookup(identity("10.0.0.1", 0))).map(Node::label).hasValue("first");
    }

    @Test
    public void nodeWithoutSnmpConfigHasNoEndpoint() throws Exception {
        final NodeRegistry registry = registry(node("bare", "10.0.0.0/24", null, null));

        final var match = registry.lookup(identity("10.0.0.1", 0));
        assertThat(match).isPresent();
        assertThat(match.get().snmpEndpoint()).isEmpty();
    }

    @Test
    public void labelDefaultsToSubnet() throws Exception {
        final NodeRegistry registry = registry(node(null, "10.0.0.0/24", null, "c"));

        assertThat(registry.lookup(identity("10.0.0.1", 0))).map(Node::label).hasValue("10.0.0.0/24");
    }
}
