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
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEndpointFactoryTest {

    private static final IPAddressString ADDRESS = new IPAddressString("10.0.0.7");

    private static CredentialSet v2c(final SecretRef community) {
        return CredentialSet.community(CredentialVersion.V2C, community);
    }

    @Test
    void buildsEndpointsForEveryVersion() {
        final CredentialSet v1 = CredentialSet.community(CredentialVersion.V1, SecretRef.of("legacy"));
        final CredentialSet v3 = CredentialSet.usm("riptide");

        final var v1Endpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v1, null, true, 161), ADDRESS);
        final var v2cEndpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), null, true, 161), ADDRESS);
        final var v3Endpoint = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v3, null, true, 161), ADDRESS);

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
        final CredentialSet v3 = new CredentialSet(CredentialVersion.V3, null, "riptide",
                TargetBuilder.AuthProtocol.sha1, SecretRef.of("env://AUTH"),
                TargetBuilder.PrivProtocol.aes128, SecretRef.of("env://PRIV"));

        final var definition = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", v3, null, true, 161), ADDRESS).get().getSnmpDefinition();

        assertThat(definition.getAuthProtocol()).isEqualTo(TargetBuilder.AuthProtocol.sha1);
        assertThat(definition.getAuthPassphrase()).isSameAs(v3.authPassphrase());
        assertThat(definition.getPrivProtocol()).isEqualTo(TargetBuilder.PrivProtocol.aes128);
        assertThat(definition.getPrivPassphrase()).isSameAs(v3.privPassphrase());
    }

    @Test
    void pollingValuesApplyAndDefaultsHoldWithoutAProfile() {
        final PollingProfile polling = new PollingProfile(
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(30), 2_000, 3);

        final var withProfile = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), polling, true, 161), ADDRESS);
        final var withoutProfile = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), null, true, 161), ADDRESS);

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
                new AgentEntry("10.0.0.7", v2c(unresolvable), null, true, 161), ADDRESS);

        assertThat(endpoint).isPresent();
        assertThat(endpoint.get().getSnmpDefinition().getCommunity()).isSameAs(unresolvable);
    }

    @Test
    void uncredentialedEntryYieldsEmpty() {
        assertThat(AgentEndpointFactory.endpointFor(new AgentEntry("10.0.0.0/24", null, null, true, 161), ADDRESS))
                .isEmpty();
    }

    @Test
    void parsedInventoryCarriesCarveOutsAndOmissionsThroughToTheEndpoint() {
        // the ACs are phrased in terms of endpointFor, so pin the whole seam once:
        // real YAML through the loader, matched through the view, into the factory.
        // This lives in org.riptide.snmp because inventory must not import snmp (AD-10)
        final var profiles = new SnmpProfilesConfig(
                java.util.Map.of("corp-v3", v3()),
                java.util.Map.of("default", PollingProfile.builtInDefault()));
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                      "10.20.99.0/24":
                        credentials: corp-v3
                        enabled: false
                      "10.21.0.0/16":
                        polling: default
                """, "seam.yaml");

        assertThat(endpointFor(snapshot, "10.20.5.5")).isPresent();
        // a credentialed carve-out still yields nothing to poll
        assertThat(endpointFor(snapshot, "10.20.99.5")).isEmpty();
        // and an uncredentialed range matches but cannot be polled either
        assertThat(endpointFor(snapshot, "10.21.5.5")).isEmpty();
    }

    private static java.util.Optional<SnmpEndpoint> endpointFor(final InventorySnapshot snapshot,
                                                                final String address) {
        final var identity = new org.riptide.pipeline.ExporterIdentity.NetflowIpfix(
                inetAddress(address), 0L);
        return snapshot.agentView().match(identity)
                .flatMap(entry -> AgentEndpointFactory.endpointFor(entry, new IPAddressString(address)));
    }

    private static java.net.InetAddress inetAddress(final String address) {
        try {
            return java.net.InetAddress.getByName(address);
        } catch (final java.net.UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }

    private static CredentialSet v3() {
        return CredentialSet.usm("riptide");
    }

    @Test
    void theConfiguredPortReachesTheEndpoint() {
        final var custom = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), null, true, 12345), ADDRESS);
        final var standard = AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), null, true, 161), ADDRESS);

        assertThat(custom.orElseThrow().getInetSocketAddress().getPort()).isEqualTo(12345);
        assertThat(standard.orElseThrow().getInetSocketAddress().getPort()).isEqualTo(161);
        // and the walk targets the address it was given, not the range key
        assertThat(custom.orElseThrow().getInetSocketAddress().getAddress().getHostAddress())
                .isEqualTo("10.0.0.7");
    }

    @Test
    void disabledEntryYieldsEmptyEvenWithCredentials() {
        // the carve-out exists to stop the walk: credentials on a disabled entry are
        // parked configuration, not an instruction to poll. A single host here because
        // the loader now rejects a wide range carrying a v1/v2c set, disabled or not
        assertThat(AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.7", v2c(SecretRef.of("public")), null, false, 161), ADDRESS))
                .isEmpty();
    }

    @Test
    void nullVersionThrowsIllegalStateExceptionNamingRange() {
        final CredentialSet set = new CredentialSet(null, null, null, null, null, null, null);
        assertThatThrownBy(() -> AgentEndpointFactory.endpointFor(
                new AgentEntry("10.0.0.0/24", set, null, true, 161), ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("no version");
    }
}
