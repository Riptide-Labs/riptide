/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.AgentEntry;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.PollingProfile;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEndpointFactoryTest {

    private static final IPAddressString ADDRESS = new IPAddressString("10.0.0.7");

    private static CredentialSet v2c(final SecretRef community) {
        final CredentialSet set = new CredentialSet();
        set.setVersion(CredentialVersion.V2C);
        set.setCommunity(community);
        return set;
    }

    @Test
    void buildsEndpointsForEveryVersion() {
        final CredentialSet v1 = new CredentialSet();
        v1.setVersion(CredentialVersion.V1);
        v1.setCommunity(SecretRef.of("legacy"));
        final CredentialSet v3 = new CredentialSet();
        v3.setVersion(CredentialVersion.V3);
        v3.setSecurityName("riptide");

        final var v1Endpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v1, null), ADDRESS);
        final var v2cEndpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v2c(SecretRef.of("public")), null), ADDRESS);
        final var v3Endpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v3, null), ADDRESS);

        assertThat(v1Endpoint).isPresent();
        assertThat(v1Endpoint.get().getSnmpDefinition().getSnmpVersion()).isEqualTo(SnmpVersion.v1);
        assertThat(v2cEndpoint).isPresent();
        assertThat(v2cEndpoint.get().getSnmpDefinition().getSnmpVersion()).isEqualTo(SnmpVersion.v2c);
        assertThat(v3Endpoint).isPresent();
        assertThat(v3Endpoint.get().getSnmpDefinition().getSnmpVersion()).isEqualTo(SnmpVersion.v3);
        assertThat(v3Endpoint.get().getSnmpDefinition().getSecurityName()).isEqualTo("riptide");
    }

    @Test
    void mapsEveryUsmFieldOntoTheDefinition() {
        // the factory's whole job is mapping fidelity: a dropped auth field would
        // silently downgrade the walk to noAuthNoPriv
        final CredentialSet v3 = new CredentialSet();
        v3.setVersion(CredentialVersion.V3);
        v3.setSecurityName("riptide");
        v3.setAuthProtocol(TargetBuilder.AuthProtocol.sha1);
        v3.setAuthPassphrase(SecretRef.of("env://AUTH"));
        v3.setPrivProtocol(TargetBuilder.PrivProtocol.aes128);
        v3.setPrivPassphrase(SecretRef.of("env://PRIV"));

        final var definition = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v3, null), ADDRESS).get().getSnmpDefinition();

        assertThat(definition.getAuthProtocol()).isEqualTo(TargetBuilder.AuthProtocol.sha1);
        assertThat(definition.getAuthPassphrase()).isSameAs(v3.getAuthPassphrase());
        assertThat(definition.getPrivProtocol()).isEqualTo(TargetBuilder.PrivProtocol.aes128);
        assertThat(definition.getPrivPassphrase()).isSameAs(v3.getPrivPassphrase());
    }

    @Test
    void pollingValuesApplyAndDefaultsHoldWithoutAProfile() {
        final PollingProfile polling = new PollingProfile();
        polling.setTimeout(2_000);
        polling.setRetries(3);

        final var withProfile = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v2c(SecretRef.of("public")), polling), ADDRESS);
        final var withoutProfile = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v2c(SecretRef.of("public")), null), ADDRESS);

        assertThat(withProfile.get().getSnmpDefinition().getTimeout()).isEqualTo(2_000);
        assertThat(withProfile.get().getSnmpDefinition().getRetries()).isEqualTo(3);
        assertThat(withoutProfile.get().getSnmpDefinition().getTimeout()).isEqualTo(500);
        assertThat(withoutProfile.get().getSnmpDefinition().getRetries()).isEqualTo(1);
    }

    @Test
    void constructionNeverResolvesSecrets() {
        // laziness by construction: the factory has no resolver access at all, so an
        // unresolvable reference must not fail until a walk actually uses it (NFR-1)
        final SecretRef unresolvable = SecretRef.of("env://RIPTIDE_TEST_MISSING_SECRET");

        final var endpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v2c(unresolvable), null), ADDRESS);

        assertThat(endpoint).isPresent();
        assertThat(endpoint.get().getSnmpDefinition().getCommunity()).isSameAs(unresolvable);
    }

    @Test
    void uncredentialedEntryYieldsEmpty() {
        assertThat(AgentEndpointFactory.endpointFor(new AgentEntry("10.0.0.0/24", null, null), ADDRESS))
                .isEmpty();
    }

    @Test
    void nullVersionThrowsIllegalStateExceptionNamingRange() {
        final CredentialSet set = new CredentialSet();
        assertThatThrownBy(() -> AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", set, null), ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("no version");
    }
}
