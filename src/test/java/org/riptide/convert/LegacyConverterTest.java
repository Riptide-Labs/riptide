/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.InventoryLoader;
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
import org.yaml.snakeyaml.Yaml;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    /** An unmappable key in a subtree the converter actively reads is a dropped setting. */
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
}
