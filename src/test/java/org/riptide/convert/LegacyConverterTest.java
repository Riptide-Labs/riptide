/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.secrets.SecretRef;
import org.yaml.snakeyaml.Yaml;

import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The converter's contract, driven end to end: every fixture is converted and then booted
 * through the real 0.9 loader.
 *
 * <p>AD-13 is the reason this suite is shaped that way. "The output always passes 0.9
 * validation" is not checkable by reading the emitted text, and a converter that emits
 * plausible YAML the loader rejects would leave an operator converting into a new error,
 * which is the one outcome the whole story exists to prevent.</p>
 */
class LegacyConverterTest {

    private static final String LEGACY = """
            riptide:
              snmp:
                poll:
                  refresh-interval-ms: 300000
                  snapshot-expiry-ms: 900000
              nodes:
                core-router:
                  subnet-address: 10.20.30.7
                  observation-domain: 42
                  snmp:
                    snmp-version: v3
                    security-name: monitoring
                    auth-protocol: hmac192sha256
                    auth-passphrase: "vault://secret/snmp/core#auth"
                  interfaces:
                    1: {alias: "Uplink to core"}
                    4093: {name: "ge-0/0/3", alias: "Peering", high-speed: 1000}
                edge-router:
                  subnetAddress: 10.20.30.8
                  snmp:
                    snmpVersion: v3
                    securityName: monitoring
                    authProtocol: hmac192sha256
                    authPassphrase: "vault://secret/snmp/core#auth"
                access-switches:
                  subnet-address: 10.20.0.0/16
                  snmp:
                    snmp-version: v2c
                    community: "env://RIPTIDE_SNMP_COMMUNITY"
                slow-agent:
                  subnet-address: 10.20.40.9
                  snmp:
                    snmp-version: v3
                    security-name: monitoring
                    auth-protocol: hmac192sha256
                    auth-passphrase: "vault://secret/snmp/core#auth"
                    timeout: 3000
                    port: 1161
                label-only:
                  subnet-address: 10.99.0.0/24
            """;

    private static LegacyConverter.Converted convert(final String legacy) {
        return LegacyConverter.convert(LegacyConfigReader.parse(legacy, "legacy.yaml"));
    }

    /**
     * AD-13, and the story's whole point: names survive. Boots the emitted pair through the
     * real loader and asserts every legacy map key still resolves for its address.
     */
    @Test
    void everyNodeKeepsItsNameThroughTheEmittedConfig() throws Exception {
        final var converted = convert(LEGACY);
        final InventorySnapshot snapshot = boot(converted);

        // core-router pinned observation domain 42, so it is reached on 42 and not on 0 —
        // faithfully, because that is what the legacy node did too. A flow from its address on
        // another domain fell through to the covering range in 0.8 and still does
        assertThat(nameFor(snapshot, "10.20.30.7", 42L)).isEqualTo("core-router");
        assertThat(nameFor(snapshot, "10.20.30.7", 0L)).isEqualTo("access-switches");
        assertThat(nameFor(snapshot, "10.20.30.8", 0L)).isEqualTo("edge-router");
        assertThat(nameFor(snapshot, "10.20.40.9", 0L)).isEqualTo("slow-agent");
        // the range-scoped node: prefix enrichment entries (2.7.1) mean it keeps its label
        // rather than reporting a loss, and it names an address nobody enumerated
        assertThat(nameFor(snapshot, "10.20.77.5", 0L)).isEqualTo("access-switches");
        // a node that was never polled is still a name
        assertThat(nameFor(snapshot, "10.99.0.3", 0L)).isEqualTo("label-only");
        // and an unpinned host entry beats the /16 that covers it
        assertThat(nameFor(snapshot, "10.20.30.8", 7L)).isEqualTo("edge-router");
    }

    @Test
    void aWideCommunityRangeIsDisabledButStillNamed() throws Exception {
        final var converted = convert(LEGACY);

        assertThat(converted.inventory())
                .contains("\"10.20.0.0/16\":")
                .contains("enabled: false");
        // no parked credential reference: the width rule fires regardless of enabled, so
        // emitting one would produce a config that cannot start. Proven by the boot below
        assertThat(converted.inventory().lines()
                .dropWhile(line -> !line.contains("\"10.20.0.0/16\""))
                .skip(1)
                .takeWhile(line -> line.startsWith("        "))
                .filter(line -> line.contains("credentials:")))
                .isEmpty();

        final InventorySnapshot snapshot = boot(converted);
        assertThat(nameFor(snapshot, "10.20.77.5", 0L)).isEqualTo("access-switches");
        assertThat(converted.summary())
                .anySatisfy(line -> assertThat(line)
                        .contains("access-switches").contains("10.20.0.0/16").contains("v2c"));
    }

    @Test
    void identicalCredentialBlocksBecomeOneSet() throws Exception {
        final var converted = convert(LEGACY);
        final Map<String, Object> credentials = credentialsOf(converted);

        // core-router, edge-router and slow-agent share one v3 block; access-switches has its
        // own v2c one. Three nodes, one set: dedup is on the credential half only, so
        // slow-agent's differing timeout must not split it
        assertThat(credentials).hasSize(2);
        assertThat(converted.summary().getFirst()).contains("2 credential set(s)");
    }

    /**
     * The case the UX worked example does not show: 0.9 keeps timeout and retries on the
     * polling profile, so a node that only changed its timeout still needs one.
     */
    @Test
    void aPerNodeTimeoutBecomesItsOwnPollingProfile() throws Exception {
        final var converted = convert(LEGACY);
        final InventorySnapshot snapshot = boot(converted);

        final var slow = snapshot.agentView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.40.9"), 0L))
                .orElseThrow();
        assertThat(slow.polling().timeout()).isEqualTo(3000);
        // and the global legacy cadence reached it too
        assertThat(slow.polling().refreshInterval()).isEqualTo(Duration.ofMinutes(5));

        final var core = snapshot.agentView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.30.7"), 0L))
                .orElseThrow();
        assertThat(core.polling().timeout()).isEqualTo(500);
        assertThat(core.polling().refreshInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aNonDefaultPortReachesTheEmittedRange() throws Exception {
        final InventorySnapshot snapshot = boot(convert(LEGACY));
        assertThat(snapshot.agentView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.40.9"), 0L))
                .orElseThrow().port()).isEqualTo(1161);
        // and a default port is not emitted as noise on every other range
        assertThat(convert(LEGACY).inventory().lines().filter(line -> line.contains("port:")).count())
                .isEqualTo(1);
    }

    @Test
    void interfacePinsSurviveInBothSpellings() throws Exception {
        final InventorySnapshot snapshot = boot(convert(LEGACY));
        final var entry = snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.30.7"), 42L))
                .orElseThrow();

        assertThat(entry.interfaces().get(1).alias()).isEqualTo("Uplink to core");
        assertThat(entry.interfaces().get(4093).name()).isEqualTo("ge-0/0/3");
        assertThat(entry.interfaces().get(4093).highSpeed()).isEqualTo(1000L);
    }

    @Test
    void theObservationDomainPinSurvives() throws Exception {
        final InventorySnapshot snapshot = boot(convert(LEGACY));
        // pinned to 42: a flow from the same address on another domain must not match it
        assertThat(snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.30.7"), 42L))
                .orElseThrow().name()).isEqualTo("core-router");
    }

    /** Two runs of one input must be byte-identical, or a re-convert produces an unreviewable diff. */
    @Test
    void conversionIsDeterministic() {
        final var first = convert(LEGACY);
        final var second = convert(LEGACY);

        assertThat(second.mainConfig()).isEqualTo(first.mainConfig());
        assertThat(second.inventory()).isEqualTo(first.inventory());
        assertThat(second.summary()).isEqualTo(first.summary());
        // and the fixture is not trivially small, or determinism would prove nothing
        assertThat(first.inventory().lines().count()).isGreaterThan(20);
    }

    @Test
    void aKeyTheConverterCannotMapIsAnErrorNamingIt() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.7
                      sysobjectid-filter: "1.3.6.1.4.1.9"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sysobjectidfilter")
                .hasMessageContaining("core-router");
    }

    @Test
    void oneFieldInBothSpellingsIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.7
                      subnetAddress: 10.20.30.8
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subnet-address")
                .hasMessageContaining("subnetAddress")
                .hasMessageContaining("map order");
    }

    @Test
    void anAddressThe09LoaderWouldRejectFailsDuringConversion() {
        // a legacy config could carry any of these; converting them would emit a file that
        // cannot start, which is exactly what AD-13 forbids
        for (final String bad : new String[] {"10.0.*.*", "010.0.0.7", "10.0.1.5/24", "10.0.1"}) {
            assertThatThrownBy(() -> convert("""
                    riptide:
                      nodes:
                        n:
                          subnet-address: "%s"
                    """.formatted(bad)))
                    .as("address %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(bad);
        }
    }

    @Test
    void aNodeWithAnSnmpBlockButNoVersionIsAnError() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.7
                      snmp:
                        community: public
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snmp-version");
    }

    @Test
    void anAliasThatYamlWouldReadAsABooleanMustBeQuotedByTheOperator() {
        // 2.7 found this on the 0.9 side: a bare `on` becomes Boolean.TRUE and would be
        // written back as "true", silently renaming an interface
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.7
                      interfaces:
                        1: {alias: on}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Boolean");
    }

    @Test
    void anEmptyOrNodelessFileSaysSoRatherThanEmittingNothing() {
        assertThatThrownBy(() -> convert("riptide:\n  clickhouse:\n    url: http://ch:8123\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no nodes");
    }

    /** Boots the emitted pair through the real 0.9 loader: this is the AD-13 proof. */
    private static InventorySnapshot boot(final LegacyConverter.Converted converted) {
        return InventoryLoader.parse(profilesFrom(converted.mainConfig()),
                converted.inventory(), "converted-inventory.yaml");
    }

    /**
     * Builds the profiles the way Spring would from the emitted main config. Hand-mapped
     * rather than run through a Spring context so the test stays a unit test; the shapes are
     * the record constructors themselves, so a mis-emitted key still fails here.
     */
    @SuppressWarnings("unchecked")
    private static SnmpProfilesConfig profilesFrom(final String mainConfig) {
        final Map<String, Object> root = new Yaml().load(mainConfig);
        final Map<String, Object> snmp =
                (Map<String, Object>) ((Map<String, Object>) root.get("riptide")).get("snmp");

        final Map<String, CredentialSet> credentials = new LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) snmp.getOrDefault("credentials", Map.of()))
                .forEach((name, body) -> credentials.put(name, credentialSet(body)));

        final Map<String, PollingProfile> polling = new LinkedHashMap<>();
        ((Map<String, Map<String, Object>>) snmp.getOrDefault("polling", Map.of()))
                .forEach((name, body) -> polling.put(name, new PollingProfile(
                        Duration.parse((String) body.get("refresh-interval")),
                        Duration.parse((String) body.get("snapshot-expiry")),
                        (Integer) body.get("timeout"),
                        (Integer) body.get("retries"))));

        return new SnmpProfilesConfig(credentials, polling);
    }

    private static CredentialSet credentialSet(final Map<String, Object> body) {
        final CredentialVersion version =
                CredentialVersion.valueOf(((String) body.get("snmp-version")).toUpperCase(java.util.Locale.ROOT));
        return version == CredentialVersion.V3
                ? CredentialSet.usm((String) body.get("security-name"))
                : CredentialSet.community(version, SecretRef.of((String) body.get("community")));
    }

    private static Map<String, Object> credentialsOf(final LegacyConverter.Converted converted) {
        final Map<String, Object> root = new Yaml().load(converted.mainConfig());
        @SuppressWarnings("unchecked")
        final Map<String, Object> snmp =
                (Map<String, Object>) ((Map<String, Object>) root.get("riptide")).get("snmp");
        @SuppressWarnings("unchecked")
        final Map<String, Object> credentials = (Map<String, Object>) snmp.get("credentials");
        return credentials;
    }

    private static String nameFor(final InventorySnapshot snapshot, final String ip, final long domain)
            throws Exception {
        return snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName(ip), domain))
                .map(entry -> entry.name())
                .orElseThrow(() -> new AssertionError("no enrichment entry matched " + ip));
    }
}
