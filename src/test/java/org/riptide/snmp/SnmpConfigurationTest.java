/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.secrets.SecretRef;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SnmpConfigurationTest {

    private static SnmpDefinition definition(final String subnet, final Long observationDomain, final String community) {
        final SnmpDefinition definition = new SnmpDefinition();
        definition.setSubnetAddress(new IPAddressString(subnet));
        definition.setObservationDomain(observationDomain);
        definition.setSnmpVersion(SnmpVersion.v2c);
        definition.setCommunity(SecretRef.of(community));
        return definition;
    }

    private static ExporterIdentity identity(final String host, final long observationDomain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), observationDomain);
    }

    private static String communityOf(final SnmpEndpoint endpoint) {
        return endpoint.getSnmpDefinition().getCommunity().getValue();
    }

    @Test
    public void observationDomainPinnedDefinitionWinsOverWildcard() throws Exception {
        final SnmpConfiguration configuration = new SnmpConfiguration();
        configuration.setDefinitions(List.of(
                definition("10.0.0.0/24", null, "wildcard"),
                definition("10.0.0.0/24", 42L, "pinned")));

        assertThat(configuration.lookup(identity("10.0.0.1", 42))).map(SnmpConfigurationTest::communityOf).hasValue("pinned");
        assertThat(configuration.lookup(identity("10.0.0.1", 7))).map(SnmpConfigurationTest::communityOf).hasValue("wildcard");
    }

    @Test
    public void pinnedOnlyConfigurationDoesNotMatchOtherDomains() throws Exception {
        final SnmpConfiguration configuration = new SnmpConfiguration();
        configuration.setDefinitions(List.of(definition("10.0.0.0/24", 42L, "pinned")));

        assertThat(configuration.lookup(identity("10.0.0.1", 42))).isPresent();
        assertThat(configuration.lookup(identity("10.0.0.1", 7))).isEmpty();
    }

    @Test
    public void subnetMissYieldsEmpty() throws Exception {
        final SnmpConfiguration configuration = new SnmpConfiguration();
        configuration.setDefinitions(List.of(definition("10.0.0.0/24", null, "wildcard")));

        assertThat(configuration.lookup(identity("192.168.1.1", 0))).isEmpty();
    }

    @Test
    public void firstSubnetMatchOrderIsKeptWithinAGroup() throws Exception {
        final SnmpConfiguration configuration = new SnmpConfiguration();
        configuration.setDefinitions(List.of(
                definition("10.0.0.0/16", null, "first"),
                definition("10.0.0.0/24", null, "second")));

        assertThat(configuration.lookup(identity("10.0.0.1", 0))).map(SnmpConfigurationTest::communityOf).hasValue("first");
    }
}
