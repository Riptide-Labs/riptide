/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.InventoryLoader;
import org.riptide.snmp.SnmpPollConfig;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.PollingProfile;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;
import org.yaml.snakeyaml.Yaml;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /**
     * The converter routes through the shared strict parser (#538). A CONTIGUOUS netmask
     * is a legal 0.8 spelling with an exact CIDR equivalent, and this tool's charter is a
     * mechanical rewrite — so it translates, emits the CIDR form, and says so in the
     * summary. A non-contiguous mask still fails: it has no CIDR equivalent, and 0.8
     * silently mis-read it as an address the operator never wrote.
     */
    @Test
    void aContiguousNetmaskIsTranslatedAndANonContiguousOneRefused() throws Exception {
        final var converted = convert("""
                riptide:
                  nodes:
                    masked:
                      subnet-address: "10.90.0.0/255.255.0.0"
                      snmp: {snmp-version: v3, security-name: mon}
                """);
        assertThat(converted.inventory()).contains("\"10.90.0.0/16\"").doesNotContain("255.255.0.0");
        assertThat(converted.summary()).anySatisfy(line -> assertThat(line)
                .contains("masked").contains("10.90.0.0/255.255.0.0").contains("10.90.0.0/16"));
        // and the emitted form boots: the translation is real, not cosmetic
        boot(converted);

        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    n:
                      subnet-address: "10.90.0.0/255.0.255.0"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("n")
                .hasMessageContaining("non-contiguous netmask")
                .hasMessageContaining("10.0.0.0");
    }

    /**
     * The inert-zone translation (#553), same charter as the netmask rewrite: 0.8
     * accepted zoned spellings and its matcher was equally zone-blind, so the converter
     * strips the zone with a summary line — and the emitted form boots. A spelling
     * combining zone AND netmask matches neither single translation and refuses with
     * the combined diagnosis instead of a silently compound rewrite.
     */
    @Test
    void aZonedAddressIsTranslatedAndAZonedNetmaskRefused() throws Exception {
        final var converted = convert("""
                riptide:
                  nodes:
                    zoned:
                      subnet-address: "fe80::1%eth0"
                      snmp: {snmp-version: v3, security-name: mon}
                """);
        assertThat(converted.inventory()).contains("\"fe80::1\"").doesNotContain("%eth0");
        assertThat(converted.summary()).anySatisfy(line -> assertThat(line)
                .contains("zoned").contains("fe80::1%eth0").contains("fe80::1")
                .contains("zone ids are ignored in matching"));
        // and the emitted form boots: the translation is real, not cosmetic
        boot(converted);

        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    zm:
                      subnet-address: "fe80::%eth0/ffff::"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zm")
                .hasMessageContaining("combines several rejected spellings");
    }

    /**
     * A collision that only exists after a rewrite names the FILE spellings: the error
     * used to print the stripped form ('fe80::1') for a node the file spells
     * 'fe80::1%eth0', with the summary line explaining the strip discarded by the throw.
     */
    @Test
    void aPostRewriteCollisionNamesTheFileSpellings() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    a:
                      subnet-address: "fe80::1"
                      snmp: {snmp-version: v3, security-name: mon}
                    b:
                      subnet-address: "fe80::1%eth0"
                      snmp: {snmp-version: v3, security-name: mon}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'a'")
                .hasMessageContaining("'b'")
                .hasMessageContaining("spells it 'fe80::1%eth0' in the file");
    }

    /**
     * The translation is shape-guarded like the netmask sibling: a zoned spelling whose
     * STRIPPED form still violates a shape rule is refused naming the ORIGINAL spelling
     * through the zone arm — the first version stripped first and then failed naming
     * 'fe80::5/64', a string that appears nowhere in the operator's file, with the
     * summary line explaining the rewrite discarded by the throw.
     */
    @Test
    void aZonedSpellingWithHostBitsRefusesNamingTheOriginal() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    zhb:
                      subnet-address: "fe80::5%eth0/64"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fe80::5%eth0/64")
                .hasMessageContaining("zone id (%eth0)")
                .hasMessageNotContaining("'fe80::5/64'");
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

    // ---- regressions from the three-layer review of PR 530 ----

    /**
     * The security classification the converter makes, against the spelling Spring accepted.
     * 0.8 bound snmp-version to an enum case-insensitively, so V2C was legal and an exact
     * compare skipped the FR-9 carve-out entirely: a live wide cleartext range, no comment,
     * no summary line, and a config that 0.9 refuses to start.
     */
    @Test
    void anUppercaseVersionStillTriggersTheCarveOut() throws Exception {
        final var converted = convert("""
                riptide:
                  nodes:
                    access:
                      subnet-address: 10.20.0.0/16
                      snmp: {snmp-version: V2C, community: "env://C"}
                """);
        assertThat(converted.inventory()).contains("enabled: false");
        assertThat(converted.summary()).anySatisfy(line -> assertThat(line).contains("access"));
        boot(converted);
    }

    /**
     * 0.8 let two nodes share an address and told them apart by observation-domain, which
     * NodeRegistry validated on the pair. 0.9 agent ranges carry no domain, so both would
     * emit the same range key: a duplicate YAML key, and an inventory that cannot load with
     * an error pointing at a generated line number instead of the two node names.
     */
    @Test
    void twoPolledNodesOnOneAddressAreRefusedByName() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-a:
                      subnet-address: 10.0.0.1
                      observation-domain: 1
                      snmp: {snmp-version: v3, security-name: mon}
                    core-b:
                      subnet-address: 10.0.0.1
                      observation-domain: 2
                      snmp: {snmp-version: v3, security-name: mon}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-a").hasMessageContaining("core-b")
                .hasMessageContaining("observation-domain");
    }

    /**
     * Credential shapes 0.8 tolerated and 0.9 rejects. Each converted cleanly and detonated
     * at the operator's next startup, naming a credential set the converter had invented,
     * which appears nowhere in their file. They now fail naming the node.
     */
    @Test
    void credentialShapesThe09LoaderRejectsFailDuringConversion() {
        record Case(String label, String snmp) { }
        for (final Case bad : new Case[] {
                new Case("v3 without security-name", "{snmp-version: v3}"),
                new Case("v2c without community", "{snmp-version: v2c}"),
                new Case("v3 carrying a community",
                        "{snmp-version: v3, security-name: mon, community: \"public\"}"),
                new Case("v2c carrying v3 fields",
                        "{snmp-version: v2c, community: \"public\", security-name: mon}"),
                new Case("auth without passphrase",
                        "{snmp-version: v3, security-name: mon, auth-protocol: hmac192sha256}"),
                new Case("an unknown version", "{snmp-version: v4, community: \"public\"}")}) {
            assertThatThrownBy(() -> convert("""
                    riptide:
                      nodes:
                        core-router:
                          subnet-address: 10.0.0.1
                          snmp: %s
                    """.formatted(bad.snmp())))
                    .as(bad.label())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("core-router");
        }
    }

    /** Numeric fields 0.8 left unchecked that 0.9 bounds. Each used to convert and fail at boot. */
    @Test
    void outOfRangeNumbersFailDuringConversion() {
        record Case(String label, String body) { }
        for (final Case bad : new Case[] {
                new Case("port above 65535", "snmp: {snmp-version: v3, security-name: m, port: 70000}"),
                new Case("port zero", "snmp: {snmp-version: v3, security-name: m, port: 0}"),
                new Case("non-positive timeout", "snmp: {snmp-version: v3, security-name: m, timeout: 0}"),
                new Case("negative retries", "snmp: {snmp-version: v3, security-name: m, retries: -1}"),
                new Case("observation-domain above 2^32-1", "observation-domain: 4294967296"),
                new Case("negative observation-domain", "observation-domain: -1")}) {
            assertThatThrownBy(() -> convert("""
                    riptide:
                      nodes:
                        core-router:
                          subnet-address: 10.0.0.1
                          %s
                    """.formatted(bad.body())))
                    .as(bad.label())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("core-router");
        }
    }

    /** A blank pin is rejected by 0.9, so it must not be emitted; 0.8 accepted the empty string. */
    @Test
    void aBlankPinFieldFailsDuringConversion() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.0.0.1
                      interfaces:
                        1: {alias: ""}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-router").hasMessageContaining("blank");
    }

    /**
     * The quoting helper escaped only backslash and quote. A newline inside an alias folded
     * into a space and the file parsed clean, silently renaming an interface; a newline in a
     * node name made the inventory unloadable.
     */
    @Test
    void controlCharactersInOperatorTextSurviveExactly() throws Exception {
        final var converted = convert("""
                riptide:
                  nodes:
                    "odd\\tname":
                      subnet-address: 10.0.0.1
                      interfaces:
                        1: {alias: "up\\nlink"}
                """);
        final InventorySnapshot snapshot = boot(converted);
        final var entry = snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.0.0.1"), 0L))
                .orElseThrow();
        // the YAML escapes above mean the legacy VALUES really contain a tab and a newline;
        // written unescaped they would fold at parse time and this would prove nothing
        assertThat(entry.name()).isEqualTo("odd\tname");
        assertThat(entry.interfaces().get(1).alias()).isEqualTo("up\nlink");
    }

    /** One ifIndex in both spellings: SnakeYAML sees two keys, so a pin would silently vanish. */
    @Test
    void oneIfIndexInBothSpellingsIsRefused() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.0.0.1
                      interfaces:
                        1: {name: "a"}
                        "1": {name: "b"}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1");
    }

    /** A node key that YAML types as something other than text would name an exporter "true". */
    @Test
    void aNodeNameThatIsNotTextIsRefused() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    on:
                      subnet-address: 10.0.0.1
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quote it");
    }

    /**
     * A key nothing binds is still an error: it is a typo, and reporting it beats leaving the
     * operator to find the setting had no effect. Contrast with the fleet-level keys below, which
     * 0.9 does bind and which are therefore none of the converter's business (#614).
     */
    @Test
    void anUnknownKeyUnderThePollSubtreeIsAnError() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 300000
                      typo-key: 5
                  nodes:
                    r: {subnet-address: 10.0.0.1}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typokey");
    }

    /** A Spring application.yaml with profile documents is common; it is not a syntax error. */
    @Test
    void aMultiDocumentFileIsNamedRatherThanCalledInvalidYaml() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes:
                    r: {subnet-address: 10.0.0.1}
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YAML documents")
                .hasMessageContaining("profile");
    }

    /** An empty 'snmp:' key is a null value, not a wrong type, and bound fine in 0.8. */
    @Test
    void anEmptySnmpSectionDoesNotAbortAConvertibleFile() throws Exception {
        boot(convert("""
                riptide:
                  snmp:
                  nodes:
                    r: {subnet-address: 10.0.0.5}
                """));
    }

    /** The relaxed lookup must reach the poll subtree too, or the cadence is silently lost. */
    @Test
    void theGlobalCadenceIsReadThroughEitherSpelling() {
        assertThat(convert("""
                riptide:
                  SNMP:
                    poll:
                      refreshIntervalMs: 300000
                  nodes:
                    r:
                      subnet-address: 10.0.0.1
                      snmp: {snmp-version: v3, security-name: mon}
                """).mainConfig()).contains("PT5M");
    }


    /**
     * The fleet-level poll keys 0.9 still binds do not block conversion (#614).
     *
     * <p>They were refused, on the reasoning that a key the converter cannot map inside a subtree it
     * reads must be a dropped setting. It is not: the converter emits a fragment the operator merges
     * into their own configuration, so a key it passes over never moves. Refusing withheld the
     * fragment entirely, and the shipped upgrade guide recommends {@code max-exporters} on the same
     * page that says to run this tool.</p>
     */
    @Test
    void fleetLevelPollKeysConvertAndAppearInNeitherOutput() {
        final var converted = convert("""
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 300000
                      pool-width: 8
                      max-exporters: 1024
                      deregister-after: 5
                      dead-endpoint-base-ms: 30000
                      dead-endpoint-ceiling-ms: 900000
                  nodes:
                    r:
                      subnet-address: 10.0.0.1
                      snmp: {snmp-version: v3, security-name: mon}
                """);

        // the mapped key still maps, so passing the siblings over did not disturb the extraction
        assertThat(converted.mainConfig()).contains("PT5M");
        for (final String passedOver : java.util.List.of(
                "pool-width", "poolWidth", "max-exporters", "maxExporters",
                "deregister-after", "dead-endpoint", "1024", "900000")) {
            assertThat(converted.mainConfig() + converted.inventory())
                    .as("%s stays in the operator's own file; the converter never moves it", passedOver)
                    .doesNotContain(passedOver);
        }
    }

    /**
     * The accepted set is derived from {@code SnmpPollConfig}, not restated in the converter.
     *
     * <p>Pinned because the failure mode of a hand-written list is silent and in the worst
     * direction: a fleet-level key added to the model later would start being <em>refused</em>,
     * blocking upgrades over a key the running version accepts. Asserting equality against the
     * model's own field names is what makes that impossible rather than merely unlikely.</p>
     */
    @Test
    void theAcceptedPollKeysAreExactlyWhatTheRunningVersionBinds() {
        final var bound = java.util.Arrays.stream(SnmpPollConfig.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(bound)
                .as("the model still declares the two the converter maps, as dead fallbacks")
                .contains("refreshintervalms", "snapshotexpiryms");

        for (final String key : bound) {
            assertThatCode(() -> convert("""
                    riptide:
                      snmp:
                        poll:
                          %s: 1000
                      nodes:
                        r: {subnet-address: 10.0.0.1}
                    """.formatted(key)))
                    .as("%s is bound by SnmpPollConfig, so the converter must not refuse it", key)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * A flat file is diagnosed by its shape, not reported as a foreign or broken document (#614).
     *
     * <p>The dotted-YAML case is the worse of the two: it parses cleanly into one key per line, so
     * the section walk found no {@code riptide} child and asked "is this a riptide configuration?"
     * of a file whose every line begins with {@code riptide.}.</p>
     */
    @Test
    void aFlatFileIsDiagnosedByItsShape() {
        assertThatThrownBy(() -> convert("riptide.nodes.core-router.subnet-address: 10.0.0.1\n"))
                .hasMessageContaining("flat")
                .hasMessageContaining("riptide.nodes.core-router.subnet-address")
                .hasMessageContaining("nested YAML")
                .hasMessageNotContaining("is this a riptide configuration?");

        // the .properties spelling fails earlier, in the parse, and gets the same fact
        assertThatThrownBy(() -> convert("riptide.nodes.core-router.subnet-address=10.0.0.1\n"))
                .hasMessageContaining("nested YAML");
    }

    /**
     * A part-flattened level is refused, not walked past.
     *
     * <p>Found in review of the fix for #614. A file can be nested at the root and flat below it —
     * {@code riptide:} whose child is {@code snmp.poll.refresh-interval-ms} — which Spring binds
     * identically to the nested form. The reader looked for a child named exactly {@code snmp},
     * found none, and carried on: the fleet cadence was dropped in <em>silence</em> and the emitted
     * profiles took 0.9's defaults. Worse than the fully-flat case, which at least errored.</p>
     */
    @Test
    void aPartFlattenedLevelIsRefusedRatherThanSilentlySkipped() {
        assertThatThrownBy(() -> convert("""
                riptide:
                  snmp.poll.refresh-interval-ms: 120000
                  nodes:
                    core: {subnet-address: 10.0.0.1}
                """))
                .as("the cadence must not vanish into a default")
                .hasMessageContaining("flat")
                .hasMessageContaining("nested YAML");

        // one level down: 'snmp:' nested, its child flattened
        assertThatThrownBy(() -> convert("""
                riptide:
                  snmp:
                    poll.refresh-interval-ms: 120000
                  nodes:
                    core: {subnet-address: 10.0.0.1}
                """))
                .hasMessageContaining("riptide.snmp")
                .hasMessageContaining("nested YAML");

        // and the nodes tree, which previously told the operator to go look in another file
        assertThatThrownBy(() -> convert("""
                riptide:
                  nodes.core-router.subnet-address: 10.0.0.1
                """))
                .hasMessageContaining("nested YAML")
                .hasMessageNotContaining("convert that one");
    }

    /**
     * A node name may legitimately contain dots, so the flatten check must not reach node names.
     *
     * <p>The guard runs at structural levels only. Naming a node after an address is the obvious
     * way to trip a blanket "any dotted key" rule.</p>
     */
    @Test
    void aNodeNamedAfterAnAddressIsNotMistakenForAFlattenedLevel() {
        assertThatCode(() -> convert("""
                riptide:
                  nodes:
                    "10.0.0.1":
                      subnet-address: 10.0.0.1
                """)).doesNotThrowAnyException();
    }

    /** The illustrating key should be a riptide one, not whatever sorted first in the document. */
    @Test
    void theFlatExampleNamesARiptideKey() {
        assertThatThrownBy(() -> convert("""
                spring.application.name: riptide
                riptide.nodes.core.subnet-address: 10.0.0.1
                """))
                .hasMessageContaining("riptide.nodes.core.subnet-address")
                .hasMessageNotContaining("('spring.application.name')");
    }

    /**
     * A nested polled pair resolves by longest prefix, deterministically, whatever domain arrives.
     *
     * <p>This is the property #615 is really about. In 0.8 the poller held one registration per
     * address and {@code register()} kept the first endpoint it resolved, so a device covered by both
     * a domain-pinned node and a wider one was polled with whichever credentials the first flow after
     * boot selected — re-decided on every restart. 0.9 replaced that race with a rule.</p>
     *
     * <p>Only the 0.9 half is asserted: {@code v0.8.1}'s {@code register()} is deleted code no test
     * in this repo can reach, so the historical claim stays in prose where a reader can check it
     * against the tag.</p>
     */
    @Test
    void aNestedPolledRangeDecidesCredentialsWhateverDomainArrives() throws Exception {
        final InventorySnapshot snapshot = boot(convert(NESTED_PIN));

        for (final long domain : new long[] {42L, 0L, 99L, 7L}) {
            final var agent = snapshot.agentView()
                    .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.30.5"), domain))
                    .orElseThrow();
            assertThat(agent.range())
                    .as("domain %d must not change which range polls 10.20.30.5", domain)
                    .isEqualTo("10.20.30.0/24");
            // the security name, not the version: both ranges in NESTED_PIN are v3, so asserting
            // the version passes whichever one won and pins nothing
            assertThat(agent.credentials().securityName()).isEqualTo("mon");
        }
    }

    /**
     * The two halves diverge, and that divergence is the whole finding — so both are pinned in one
     * test rather than in two that could be read apart.
     *
     * <p>Naming still honours the pin. Polling follows longest prefix. An operator whose mental model
     * is "domain 42 means v3" is right about the name and no longer right about the credentials.</p>
     */
    @Test
    void thePinStillDecidesTheNameWhileTheRangeDecidesPolling() throws Exception {
        final InventorySnapshot snapshot = boot(convert(NESTED_PIN));

        assertThat(nameFor(snapshot, "10.20.30.5", 42L))
                .as("the pin decides the name on its own domain")
                .isEqualTo("core-router");
        assertThat(nameFor(snapshot, "10.20.30.5", 99L))
                .as("and a different domain falls through to the covering entry, as in 0.8")
                .isEqualTo("access-switches");

        assertThat(snapshot.agentView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.20.30.5"), 99L))
                .orElseThrow().range())
                .as("while polling ignores the domain entirely")
                .isEqualTo("10.20.30.0/24");
    }

    /** The conversion is correct, so it is reported and still emitted — never refused. */
    @Test
    void aPinnedNodeInsideAnotherPolledRangeIsReportedNotRefused() {
        final var converted = convert(NESTED_PIN);

        assertThat(converted.summary())
                .anySatisfy(line -> assertThat(line)
                        .contains("core-router")
                        .contains("access-switches")
                        .contains("42"));
        assertThat(converted.inventory())
                .as("a correct output exists, so the conversion must still happen")
                .contains("10.20.30.0/24");
    }

    /** A pinned node nothing covers never had a second candidate, so nothing changed for it. */
    @Test
    void anUnnestedPinnedNodeIsNotReported() {
        final var converted = convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.0/24
                      observation-domain: 42
                      snmp: {snmp-version: v3, security-name: mon}
                """);

        assertThat(converted.summary())
                .as("reporting the common case would bury the line that matters")
                .noneSatisfy(line -> assertThat(line).contains("sits inside polled range"));
    }

    /**
     * A carve-out that wins the match is reported as polling nothing, not as polling with its own
     * credentials.
     *
     * <p>An FR-9 range is emitted {@code enabled: false} with no credentials, and it still wins the
     * longest-prefix match, so it shadows the wider range rather than deferring to it. The first
     * version of this report said "always polled with 'core-router' credentials" while the carve-out
     * line two lines above said polling stops — two contradictory claims about the same node, on
     * v2c-on-a-subnet, which is the commonest legacy shape there is.</p>
     */
    @Test
    void aCarvedOutWinnerIsReportedAsPollingNothing() {
        final var converted = convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.30.0/24
                      observation-domain: 42
                      snmp: {snmp-version: v2c, community: public}
                    access-switches:
                      subnet-address: 10.20.0.0/16
                      snmp: {snmp-version: v3, security-name: fleet}
                """);

        assertThat(converted.summary())
                .anySatisfy(line -> assertThat(line)
                        .contains("overlaps polled range")
                        .contains("polled by nothing"))
                .noneSatisfy(line -> assertThat(line)
                        .contains("overlaps polled range")
                        .contains("polled with 'core-router' credentials"));
    }

    /**
     * The fall-through named is the most specific overlapping range, not the first one found.
     *
     * <p>Ranges are collected in sorted node-name order, which has nothing to do with specificity.
     * Breaking on the first match named {@code aaa-wide} (a /8) where 0.8 would actually have fallen
     * through to {@code zzz-mid} (a /16), pointing the operator at the wrong credentials.</p>
     */
    @Test
    void theMostSpecificOverlappingRangeIsNamedNotTheFirstFound() {
        final var converted = convert("""
                riptide:
                  nodes:
                    aaa-wide:
                      subnet-address: 10.0.0.0/8
                      snmp: {snmp-version: v3, security-name: wide}
                    zzz-mid:
                      subnet-address: 10.20.0.0/16
                      snmp: {snmp-version: v3, security-name: mid}
                    core-router:
                      subnet-address: 10.20.30.0/24
                      observation-domain: 42
                      snmp: {snmp-version: v3, security-name: core}
                """);

        assertThat(converted.summary())
                .anySatisfy(line -> assertThat(line)
                        .contains("overlaps polled range 'zzz-mid'")
                        .doesNotContain("aaa-wide"));
    }

    /**
     * A pinned range that <em>contains</em> an unpinned one raced identically, and was unreported.
     *
     * <p>{@code PinnedPrefixMatcher} consults the pinned pool first, so a pinned wider range beats an
     * unpinned narrower one for its own domain while the narrower one serves every other domain —
     * the same two endpoints for one address. Testing only "pinned inside another" missed exactly the
     * population this report exists to warn.</p>
     */
    @Test
    void aPinnedRangeContainingAnUnpinnedOneIsAlsoReported() {
        final var converted = convert("""
                riptide:
                  nodes:
                    core-router:
                      subnet-address: 10.20.0.0/16
                      observation-domain: 42
                      snmp: {snmp-version: v3, security-name: mon}
                    access-switches:
                      subnet-address: 10.20.30.0/24
                      snmp: {snmp-version: v3, security-name: fleet}
                """);

        assertThat(converted.summary())
                .anySatisfy(line -> assertThat(line)
                        .contains("core-router")
                        .contains("overlaps polled range 'access-switches'")
                        // the narrower range wins for the addresses both cover
                        .contains("polled with 'access-switches' credentials"));
    }

    /** A domain-pinned /24 inside a polled /16, both with SNMP: the shape 0.8 raced on. */
    private static final String NESTED_PIN = """
            riptide:
              nodes:
                core-router:
                  subnet-address: 10.20.30.0/24
                  observation-domain: 42
                  snmp: {snmp-version: v3, security-name: mon}
                access-switches:
                  subnet-address: 10.20.0.0/16
                  snmp: {snmp-version: v3, security-name: fleet}
            """;

    /** Boots the emitted pair through the real 0.9 loader: this is the AD-13 proof. */
    private static InventorySnapshot boot(final LegacyConverter.Converted converted) {
        return InventoryLoader.parse(profilesFrom(converted.mainConfig()),
                converted.inventory(), "converted-inventory.yaml");
    }

    /**
     * Binds the emitted main config with Spring's own {@code Binder}, exactly as
     * {@code ConfigFileReloader} does.
     *
     * <p>Two earlier versions of this helper were wrong in the same way. The first read three
     * keys by name and dropped the rest; the second read all of them by name. Both agreed with
     * whatever the converter emitted, so neither could see a mis-spelled key — and
     * {@code @ConfigurationProperties} defaults to {@code ignoreUnknownFields = true}, which
     * means a wrong key binds to nothing and fails startup with a message about a missing
     * value. That is exactly the bug this caught once running through the real binder:
     * the emitted credential key was {@code snmp-version}, and the record component is
     * {@code version}.</p>
     */
    private static SnmpProfilesConfig profilesFrom(final String mainConfig) {
        // an empty source stack, not StandardEnvironment: that one carries systemProperties and
        // systemEnvironment, so a machine exporting RIPTIDE_SNMP_CREDENTIALS_* would bind its
        // own values into the object under assertion alongside the converter's output
        final var sources = new org.springframework.core.env.MutablePropertySources();
        try {
            new YamlPropertySourceLoader()
                    .load("converted", new ByteArrayResource(mainConfig.getBytes(StandardCharsets.UTF_8)))
                    .forEach(sources::addFirst);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("emitted main config is not loadable YAML", e);
        }
        final var binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources),
                // deliberately the shared instance, NOT the context's binding converters:
                // the converter never emits blank values, and the shared path is STRICTER
                // there (it throws where SecretRefConverter maps blank to null), which is
                // the safe direction for a validity proof. See #533 for the production
                // reloader, where the same substitution was a bug
                ApplicationConversionService.getSharedInstance());
        // SnmpProfilesConfig validates every set and profile in its canonical constructor, so
        // binding IS the AD-13 assertion
        return binder.bind("riptide.snmp", Bindable.of(SnmpProfilesConfig.class))
                .orElseGet(() -> new SnmpProfilesConfig(Map.of(), Map.of()));
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

    /** The legacy poll block both optional-key fixtures share; every value is non-default. */
    private static final String LEGACY_POLL = """
            riptide:
              snmp:
                poll:
                  refresh-interval-ms: 300000
                  snapshot-expiry-ms: 900000
              nodes:
            """;

    /**
     * <b>authNoPriv</b>, and alone in its fixture, which is what makes it evidence.
     *
     * <p>In an authPriv set the auth pair is effectively mandatory: {@code
     * CredentialSet.validateUsm} rejects priv-without-auth, so a mis-spelled auth key there
     * fails at bind time and the existing harness already catches it. Only with priv absent
     * does dropping the auth pair produce a <em>legal</em> noAuthNoPriv set that validates
     * clean. And it has to be the only node in the file: any authPriv sibling would fail the
     * same bind, so the mis-spelling would be caught by that sibling rather than by the value
     * assertion this fixture exists for.</p>
     */
    private static final String LEGACY_AUTH_ONLY = LEGACY_POLL + """
                auth-only:
                  subnet-address: 10.40.50.60
                  snmp:
                    snmp-version: v3
                    security-name: authonly
                    auth-protocol: hmac192sha256
                    auth-passphrase: "vault://secret/snmp/authonly#auth"
                    timeout: 4000
                    retries: 7
            """;

    /** <b>authPriv</b>, alone for the mirror reason: dropping the priv pair leaves a legal authNoPriv set. */
    private static final String LEGACY_AUTH_PRIV = LEGACY_POLL + """
                auth-priv:
                  subnet-address: 10.40.50.61
                  snmp:
                    snmp-version: v3
                    security-name: authpriv
                    auth-protocol: hmac192sha256
                    auth-passphrase: "vault://secret/snmp/authpriv#auth"
                    priv-protocol: aes256
                    priv-passphrase: "vault://secret/snmp/authpriv#priv"
                    timeout: 4000
                    retries: 7
            """;

    /**
     * An emitted auth pair that binds to nothing degrades the set to noAuthNoPriv, silently (#544).
     *
     * <p>{@code profilesFrom} catches a mis-spelled <em>mandatory</em> key on its own, because
     * binding to nothing makes validation throw — that is how the shipped {@code snmp-version}
     * -vs-{@code version} bug was found. It cannot catch this one: the result is a legal set,
     * so in production every walk for it would go out unauthenticated with a green suite. Only
     * the value can tell.</p>
     *
     * <p>Whole-record equality, so the record's <em>enumeration</em> is pinned too: a new
     * optional component on {@code CredentialSet} makes this a compile error and forces the
     * coverage question, where a list of getters would leave it silently uncovered.</p>
     */
    @Test
    void anAuthPairThatBindsToNothingIsCaughtByItsValue() {
        final var profiles = profilesFrom(convert(LEGACY_AUTH_ONLY).mainConfig());

        assertThat(theOnlySet(profiles))
                .as("dropping the auth pair here is a LEGAL noAuthNoPriv set: nothing but this"
                        + " value stands between a typo and unauthenticated SNMP")
                .isEqualTo(new CredentialSet(CredentialVersion.V3, null, "authonly",
                        TargetBuilder.AuthProtocol.hmac192sha256,
                        SecretRef.of("vault://secret/snmp/authonly#auth"), null, null));
    }

    /** The mirror: an emitted priv pair that binds to nothing leaves a legal authNoPriv set (#544). */
    @Test
    void aPrivPairThatBindsToNothingIsCaughtByItsValue() {
        final var profiles = profilesFrom(convert(LEGACY_AUTH_PRIV).mainConfig());

        assertThat(theOnlySet(profiles))
                .as("dropping the priv pair leaves a legal authNoPriv set, so every walk for it"
                        + " would go out unencrypted with the suite still green")
                .isEqualTo(new CredentialSet(CredentialVersion.V3, null, "authpriv",
                        TargetBuilder.AuthProtocol.hmac192sha256,
                        SecretRef.of("vault://secret/snmp/authpriv#auth"),
                        TargetBuilder.PrivProtocol.aes256,
                        SecretRef.of("vault://secret/snmp/authpriv#priv")));
    }

    /**
     * The polling profile survives the round trip with every value (#544).
     *
     * <p>Of the four keys, {@code refresh-interval} and {@code timeout} are already pinned by
     * {@code aPerNodeTimeoutBecomesItsOwnPollingProfile}; {@code snapshot-expiry} and {@code
     * retries} are what this adds. The whole record covers all four in one place and pins the
     * enumeration, which is cheaper than arguing about which two are new.</p>
     *
     * <p>Routed through {@link #boot} like every sibling here, so it proves the profile is also
     * <em>referenced</em> by the range: binding alone would pass on a correct profile that no
     * agent points at.</p>
     */
    @Test
    void thePollingProfileSurvivesTheRoundTripWithEveryValue() throws Exception {
        final var snapshot = boot(convert(LEGACY_AUTH_ONLY));

        final var agent = snapshot.agentView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.40.50.60"), 0L))
                .orElseThrow(() -> new AssertionError("the converted range did not match"));

        assertThat(agent.polling())
                .as("every polling key carries a @DefaultValue, so a mis-spelled one binds to the"
                        + " default rather than failing and only the value can catch it")
                .isEqualTo(new PollingProfile(Duration.ofMinutes(5), Duration.ofMinutes(15), 4000, 7));
        assertThat(agent.polling())
                .as("and the fixture must differ from the built-in default, or a mis-spelling"
                        + " would be indistinguishable from a correct bind")
                .isNotEqualTo(PollingProfile.builtInDefault());
    }

    /** The fixture's single credential set; fails loudly rather than picking one of several. */
    private static CredentialSet theOnlySet(final SnmpProfilesConfig profiles) {
        assertThat(profiles.credentials())
                .as("the fixture is deliberately one node, so an assertion cannot pick the wrong set")
                .hasSize(1);
        return profiles.credentials().values().iterator().next();
    }

}
