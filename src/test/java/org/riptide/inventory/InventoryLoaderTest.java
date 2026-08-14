/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryLoaderTest {

    @TempDir
    Path tempDir;

    private static SnmpProfilesConfig profiles() {
        return new SnmpProfilesConfig(
                Map.of("corp-v3", TestCredentials.v3()),
                Map.of("default", new PollingProfile()));
    }

    @Test
    void validInventoryParsesResolvesAndServes() {
        final SnmpProfilesConfig profiles = profiles();
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                        polling: default
                      "10.99.0.7": {}
                  exporters:
                    core-router-1:
                      address: 10.20.0.1
                      observation-domain: 42
                    lab-switch:
                      address: 10.20.0.1
                """, "test.yaml");

        final var agent = snapshot.agentView().match(netflow("10.20.5.5", 0));
        assertThat(agent).isPresent();
        // resolved at build time to the object itself, never re-looked-up by name (AD-5)
        assertThat(agent.get().credentials()).isSameAs(profiles.credentials().get("corp-v3"));
        assertThat(agent.get().polling()).isSameAs(profiles.polling().get("default"));
        assertThat(snapshot.agentView().match(netflow("10.99.0.7", 0))).isPresent();
        assertThat(snapshot.agentView().match(netflow("192.168.1.1", 0))).isEmpty();

        // the observation-domain pin beats the unpinned entry for the same address
        assertThat(snapshot.exporterView().match(netflow("10.20.0.1", 42)))
                .map(ExporterEntry::name).hasValue("core-router-1");
        assertThat(snapshot.exporterView().match(netflow("10.20.0.1", 7)))
                .map(ExporterEntry::name).hasValue("lab-switch");
    }

    @Test
    void unsetFileYieldsTheValidEmptyInventory() {
        final var snapshot = InventoryLoader.load(profiles(), null);

        assertThat(snapshot.agentView().match(netflow("10.0.0.1", 0))).isEmpty();
        assertThat(snapshot.exporterView().match(netflow("10.0.0.1", 0))).isEmpty();
    }

    @Test
    void unreadableFileFailsNamingThePath() {
        final Path missing = this.tempDir.resolve("missing.yaml");

        assertThatThrownBy(() -> InventoryLoader.load(profiles(), missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable")
                .hasMessageContaining("missing.yaml");
    }

    @Test
    void invalidYamlFailsNamingTheFile() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), "riptide: [unclosed", "broken.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid YAML")
                .hasMessageContaining("broken.yaml");
    }

    @Test
    void duplicateCoverageFailsNamingBothEntries() {
        // distinct spellings, same canonical slot: the matcher's displacement check names both
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.7": {}
                      "10.0.0.7/32": {}
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.7")
                .hasMessageContaining("10.0.0.7/32");
    }

    @Test
    void literalDuplicateKeysFailAtParse() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24": {}
                      "10.0.0.0/24": {}
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void unknownCredentialReferenceFailsNamingBothSides() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        credentials: nope
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("credential set 'nope'");
    }

    @Test
    void unknownPollingReferenceFailsNamingBothSides() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        polling: warp-speed
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("polling profile 'warp-speed'");
    }

    @Test
    void unknownEntryKeyFailsNamingEntryAndKey() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        communtiy: public
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("communtiy");
    }

    @Test
    void unparseableRangeKeyFailsNamingTheEntry() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "not-an-ip": {}
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-an-ip");
    }

    @Test
    void legacyShapesAreRejectedNotInherited() {
        // strict from birth: the riptide.nodes leniency (ranges, wildcard forms,
        // host-bits-set prefixes) does not exist in the new trees
        for (final String shape : new String[]{"10.0.1-3.*", "10.0.*.*", "10.0.1.5/24", "*"}) {
            assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                    riptide:
                      snmp:
                        agents:
                          "%s": {}
                    """.formatted(shape), "test.yaml"))
                    .as("shape %s", shape)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(shape);
        }
    }

    @Test
    void omittedPollingResolvesTheOperatorDefinedDefault() {
        final SnmpProfilesConfig profiles = profiles();
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """, "test.yaml");

        assertThat(snapshot.agentView().match(netflow("10.20.5.5", 0)).get().polling())
                .isSameAs(profiles.polling().get("default"));
    }

    @Test
    void omittedPollingFallsBackToTheBuiltInDefault() {
        // no operator-defined default profile at all
        final SnmpProfilesConfig profiles = new SnmpProfilesConfig(
                Map.of("corp-v3", TestCredentials.v3()), Map.of());
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """, "test.yaml");

        final var polling = snapshot.agentView().match(netflow("10.20.5.5", 0)).get().polling();
        assertThat(polling).isNotNull();
        assertThat(polling.getRefreshInterval()).isEqualTo(java.time.Duration.ofMillis(600_000));
    }

    @Test
    void explicitDefaultReferenceBehavesLikeTheOmittedKey() {
        // spelling out "polling: default" must not fail where omission succeeds,
        // even when the operator defines no default profile
        final SnmpProfilesConfig profiles = new SnmpProfilesConfig(
                Map.of("corp-v3", TestCredentials.v3()), Map.of());
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                        polling: default
                      "10.30.0.0/16":
                        credentials: corp-v3
                """, "test.yaml");

        final var explicit = snapshot.agentView().match(netflow("10.20.5.5", 0)).get().polling();
        final var omitted = snapshot.agentView().match(netflow("10.30.5.5", 0)).get().polling();
        assertThat(explicit).isSameAs(omitted);
        assertThat(explicit.getRefreshInterval()).isEqualTo(java.time.Duration.ofMillis(600_000));
    }

    @Test
    void ipv6RangesAndExportersWork() {
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "2001:db8::/32":
                        credentials: corp-v3
                  exporters:
                    v6-core:
                      address: "2001:db8::1"
                """, "test.yaml");

        assertThat(snapshot.agentView().match(netflow("2001:db8:1::5", 0))).isPresent();
        assertThat(snapshot.exporterView().match(netflow("2001:db8::1", 0)))
                .map(ExporterEntry::name).hasValue("v6-core");
        assertThat(snapshot.agentView().match(netflow("10.0.0.1", 0))).isEmpty();
    }

    @Test
    void unknownTreeLevelKeysFailNamingTheKey() {
        // a typo'd section must not silently mean an empty inventory
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agnets:
                      "10.0.0.0/24": {}
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agnets")
                .hasMessageContaining("riptide.snmp");

        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporterz:
                    x:
                      address: 10.0.0.1
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exporterz");
    }

    @Test
    void nonStringKeysFailNamingTheKeyNotWithAClassCast() {
        // SnakeYAML 1.1 implicit typing: an unquoted `on:` arrives as Boolean true
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    on:
                      address: 10.0.0.1
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quote");
    }

    @Test
    void observationDomainTypeAndRangeAreValidated() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    quoted:
                      address: 10.0.0.1
                      observation-domain: "42"
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quoted")
                .hasMessageContaining("whole number");

        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    negative:
                      address: 10.0.0.1
                      observation-domain: -1
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative")
                .hasMessageContaining("unsigned 32-bit");
    }

    @Test
    void inetAtonSpellingsAreRejectedNotReinterpreted() {
        // "10.0.1" would quietly mean 10.0.0.1 and "010.0.0.7" octal 8.0.0.7;
        // a typo must fail, not match a different address
        for (final String spelling : new String[]{"10.0.1", "010.0.0.7", "167772161", "10.20"}) {
            assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                    riptide:
                      snmp:
                        agents:
                          "%s": {}
                    """.formatted(spelling), "test.yaml"))
                    .as("spelling %s", spelling)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(spelling);
        }
    }

    @Test
    void exporterAddressMustBeASingleHost() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    wide:
                      address: 10.20.0.0/24
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wide")
                .hasMessageContaining("single host");
    }

    @Test
    void buildPhaseErrorsNameTheInventoryFile() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.7": {}
                      "10.0.0.7/32": {}
                """, "inventory.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.yaml");
    }

    @Test
    void exporterWithoutAddressFailsNamingTheEntry() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    nameless:
                      observation-domain: 1
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nameless")
                .hasMessageContaining("address");
    }

    @Test
    void inventoryLargerThanTheDefaultCodePointLimitParses() {
        // the SnakeYAML default (3 MB of code points) would truncate this; the
        // loader sets its limit explicitly
        final StringBuilder content = new StringBuilder(4_000_000)
                .append("riptide:\n  snmp:\n    agents:\n");
        for (int i = 0; i < 45_000; i++) {
            content.append("      \"10.").append(i / 250 % 250).append('.').append(i % 250).append(".0/24\":\n")
                    .append("        credentials: corp-v3\n")
                    .append("        polling: default\n");
        }
        assertThat(content.length()).isGreaterThan(3_145_728);

        final var snapshot = InventoryLoader.parse(profiles(), content.toString(), "big.yaml");

        assertThat(snapshot.agentView().match(netflow("10.1.2.3", 0))).isPresent();
    }

    private static ExporterIdentity netflow(final String address, final long domain) {
        try {
            return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), domain);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
