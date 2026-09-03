/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class InventoryLoaderTest {

    @TempDir
    Path tempDir;

    private static SnmpProfilesConfig profiles() {
        return new SnmpProfilesConfig(
                Map.of("corp-v3", TestCredentials.v3()),
                Map.of("default", PollingProfile.builtInDefault(), "slow", PollingProfile.builtInDefault()));
    }

    /**
     * A file written by an editor that prefixes a UTF-8 BOM loads exactly as one without (#725).
     *
     * <p>The issue predicted the first key parsing as a BOM-prefixed name and matching nothing.
     * Measured against the pinned SnakeYAML, it does not: a leading BOM is stripped on the
     * {@code String} overload as well as the {@code InputStream} one, so this already worked. It is
     * pinned because {@code load} now removes the BOM itself rather than relying on that, and this
     * is the assertion that the removal takes no content with it.</p>
     */
    @Test
    void aByteOrderMarkOnTheFrontOfTheFileChangesNothing(@TempDir final Path dir) throws Exception {
        final String body = """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                        polling: default
                """;
        final Path file = dir.resolve("inventory.yaml");
        Files.write(file, ("\uFEFF" + body).getBytes(StandardCharsets.UTF_8));

        final var snapshot = InventoryLoader.load(profiles(), file).snapshot();

        assertThat(snapshot.agentView().match(netflow("10.20.5.5", 0)))
                .as("the BOM must not stop the agents section resolving")
                .isPresent();
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
        final var snapshot = InventoryLoader.load(profiles(), null).snapshot();

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

    /**
     * An agent range must never accept an observation-domain pin (#543).
     *
     * <p>This is a tripwire, not a validation test. Accepting the key would be a two-line change to
     * {@code AGENT_KEYS} that looks harmless and is not: {@code InterfaceSnapshotPoller.resolve}
     * re-resolves a registration <em>by address alone</em>, synthesising
     * {@code new ExporterIdentity.NetflowIpfix(address, 0L)}. That hardcoded {@code 0} is exact only
     * because every agent range lands in {@code PinnedPrefixMatcher}'s wildcard pool.</p>
     *
     * <p>Pin a range and its registrations resolve empty in the poller, get marked stop-when-idle and
     * are deregistered on the next tick — while {@code SnmpEnricher}, which matches with the flow's
     * real domain, keeps handing the endpoint straight back. An infinite register/deregister loop
     * over the entire pinned population, with no error anywhere.</p>
     *
     * <p>{@code InterfaceSnapshotPollerTest#agentRangesResolveRegardlessOfObservationDomain}, in
     * another package, cannot catch this: its
     * fixture declares no pin, so the entry it parses is unpinned whether or not the loader has
     * learned to accept one. The property has to be asserted against an inventory that <em>does</em>
     * declare a pin, which is why this test exists rather than that one being extended.</p>
     *
     * <p>The pin belongs on exporter entries, which is where naming happens and where it is honoured.
     * See {@code AgentEntry}'s javadoc for why polling cannot carry one at all.</p>
     */
    @Test
    void anAgentRangeRejectsAnObservationDomainPin() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.32.0.0/24":
                        credentials: corp-v3
                        observation-domain: 42
                """, "test.yaml"))
                .as("accepting this silently deregisters the pinned fleet — see #543")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("observation-domain");

        // and it is still accepted where it belongs, so this is a tripwire and not a ban
        assertThatCode(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core-router:
                      address: 10.32.0.0/24
                      observation-domain: 42
                """, "test.yaml"))
                .doesNotThrowAnyException();
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
        assertThat(polling.refreshInterval()).isEqualTo(java.time.Duration.ofMinutes(10));
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
        assertThat(explicit.refreshInterval()).isEqualTo(java.time.Duration.ofMinutes(10));
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
    void exporterAddressMayBeAPrefixOrAHostAndMostSpecificWins() {
        // a prefix entry labels and pins every device it covers, which is how a site
        // scoped label survives the move off riptide.nodes
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    region:
                      address: 10.20.0.0/16
                    site:
                      address: 10.20.30.0/24
                      interfaces:
                        1: { alias: "Uplink, pinned for the whole site" }
                    one-device:
                      address: 10.20.30.7
                """, "test.yaml");

        assertThat(snapshot.exporterView().match(netflow("10.20.99.9", 0)))
                .map(ExporterEntry::name).hasValue("region");
        assertThat(snapshot.exporterView().match(netflow("10.20.30.9", 0)))
                .map(ExporterEntry::name).hasValue("site");
        // the bare host stays the most specific match, even inside both prefixes
        assertThat(snapshot.exporterView().match(netflow("10.20.30.7", 0)))
                .map(ExporterEntry::name).hasValue("one-device");
        // and a prefix entry's pins reach every device it covers
        assertThat(snapshot.exporterView().match(netflow("10.20.30.9", 0)).orElseThrow()
                .interfaces().get(1).alias()).isEqualTo("Uplink, pinned for the whole site");
    }

    @Test
    void exporterPrefixesKeepThePinAndDuplicateRules() {
        // a pinned prefix beats an unpinned one covering the same address, and the
        // unpinned one still serves every other domain
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    pinned:
                      address: 10.21.0.0/24
                      observation-domain: 42
                    unpinned:
                      address: 10.21.0.0/24
                """, "test.yaml");

        assertThat(snapshot.exporterView().match(netflow("10.21.0.5", 42)))
                .map(ExporterEntry::name).hasValue("pinned");
        assertThat(snapshot.exporterView().match(netflow("10.21.0.5", 7)))
                .map(ExporterEntry::name).hasValue("unpinned");

        // two spellings of one canonical coverage with the same pin are ambiguous; the
        // host-vs-/32 pair proves this is canonicalisation rather than string equality.
        // (The netmask form used to be the proof here; #538 rejects the whole netmask
        // spelling class at the parse boundary, which subsumes that ambiguity — pinned
        // in StrictAddressDiagnosisTest)
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    first:
                      address: 10.22.0.7
                    second:
                      address: 10.22.0.7/32
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first")
                .hasMessageContaining("second");
    }

    @Test
    void exporterAddressesStayStrictAboutEveryOtherShape() {
        // lifting host-only must not lift the strictness that catches typos: the
        // exporters tree shares strictAddress with agent ranges, asserted not assumed
        for (final String shape : new String[]{"10.0.*.*", "10.0.1-3.*", "*", "10.0.1.5/24", "010.0.0.7", "167772161"}) {
            assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                    riptide:
                      exporters:
                        bad:
                          address: "%s"
                    """.formatted(shape), "test.yaml"))
                    .as("shape %s", shape)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bad");
        }
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

    @Test
    void disabledRangeShadowsTheWiderCredentialedRange() {
        // the carve-out is an ordinary trie entry: longest prefix already shadows,
        // the flag only tells consumers not to poll what it matched
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                      "10.20.99.0/24":
                        enabled: false
                """, "test.yaml");

        final var carvedOut = snapshot.agentView().match(netflow("10.20.99.5", 0)).orElseThrow();
        assertThat(carvedOut.range()).isEqualTo("10.20.99.0/24");
        assertThat(carvedOut.enabled()).isFalse();

        final var covered = snapshot.agentView().match(netflow("10.20.5.5", 0)).orElseThrow();
        assertThat(covered.enabled()).isTrue();
        assertThat(covered.credentials()).isNotNull();
    }

    @Test
    void enabledAcceptsExplicitTrueAndYamlNullAndRejectsNonBooleans() {
        // YAML 1.1 turns no/off into booleans but quoted "false" into a String: a
        // silent truthy string would carve out nothing while looking deliberate
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.1.0.0/24":
                        enabled: true
                        credentials: corp-v3
                      "10.2.0.0/24":
                        enabled:
                        credentials: corp-v3
                      "10.3.0.0/24":
                        enabled: off
                """, "test.yaml");

        assertThat(snapshot.agentView().match(netflow("10.1.0.5", 0)).orElseThrow().enabled()).isTrue();
        // explicit YAML null reads as absent, matching credentials/polling
        assertThat(snapshot.agentView().match(netflow("10.2.0.5", 0)).orElseThrow().enabled()).isTrue();
        assertThat(snapshot.agentView().match(netflow("10.3.0.5", 0)).orElseThrow().enabled()).isFalse();

        // the offending value must be interpolated: asserting on "false" would be
        // satisfied by the message's own "true or false" remediation clause
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.4.0.0/24":
                        enabled: nope
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.4.0.0/24")
                .hasMessageContaining("nope");
    }

    @Test
    void carveOutsOnlyShadowOutwardAndCannotDuplicateTheRangeTheyExclude() {
        // stated in tests deliberately: an operator who spells the exclusion WIDER
        // than the range it means to silence gets nothing, because longest prefix
        // still picks the narrower credentialed range
        final var inverted = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/8":
                        enabled: false
                      "10.20.0.0/16":
                        credentials: corp-v3
                """, "test.yaml");

        final var stillPolled = inverted.agentView().match(netflow("10.20.5.5", 0)).orElseThrow();
        assertThat(stillPolled.range()).isEqualTo("10.20.0.0/16");
        assertThat(stillPolled.enabled()).isTrue();
        // the carve-out still applies where nothing narrower covers the address
        assertThat(inverted.agentView().match(netflow("10.30.5.5", 0)).orElseThrow().enabled()).isFalse();

        // and a carve-out at the same prefix as the range it excludes is a duplicate,
        // not a winner: ambiguity fails startup naming both entries
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.40.0.0/16":
                        credentials: corp-v3
                      "10.40.0.0/255.255.0.0":
                        enabled: false
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.40.0.0/16")
                .hasMessageContaining("10.40.0.0/255.255.0.0");
    }

    @Test
    void emptyEntryWarnsNamingTheRangeButStillMatches() {
        final var logger = (Logger) LoggerFactory.getLogger(InventoryLoader.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            final var snapshot = InventoryLoader.parse(profiles(), """
                    riptide:
                      snmp:
                        agents:
                          "10.50.0.0/24":
                          "10.51.0.0/24": {}
                          "10.52.0.0/24":
                            credentials:
                    """, "test.yaml");

            assertThat(snapshot.agentView().match(netflow("10.50.0.5", 0))).isPresent();
            assertThat(snapshot.agentView().match(netflow("10.51.0.5", 0))).isPresent();
            assertThat(warnings(appender, "10.50.0.0/24")).isEqualTo(1);
            assertThat(warnings(appender, "10.51.0.0/24")).isEqualTo(1);
            // the shape operators actually produce: key typed, value not filled in.
            // It builds the same serving state as {} and must be just as loud
            assertThat(warnings(appender, "10.52.0.0/24")).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void deliberateEntriesNeverWarn() {
        // a carve-out, a not-polled-but-profiled range, and a parked credentialed
        // range are all intentional shapes: only a body with nothing in it is a typo
        final var logger = (Logger) LoggerFactory.getLogger(InventoryLoader.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            InventoryLoader.parse(profiles(), """
                    riptide:
                      snmp:
                        agents:
                          "10.60.0.0/24":
                            enabled: false
                          "10.61.0.0/24":
                            polling: slow
                          "10.62.0.0/24":
                            credentials: corp-v3
                            enabled: false
                    """, "test.yaml");

            // filtered to WARN on purpose: this test is about warnings, so a future
            // log.info in the loader must not break it
            assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void omittedCredentialsStillMatchesAndCarriesTheProfile() {
        final SnmpProfilesConfig profiles = profiles();
        final var snapshot = InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.70.0.0/24":
                        polling: default
                """, "test.yaml");

        final var entry = snapshot.agentView().match(netflow("10.70.0.5", 0)).orElseThrow();
        assertThat(entry.credentials()).isNull();
        assertThat(entry.polling()).isSameAs(profiles.polling().get("default"));
        assertThat(entry.enabled()).isTrue();
    }

    @Test
    void sflowIdentitiesResolveThroughTheAgentViewByAgentAddress() {
        // AD-11's other half (the UDP source is never consulted for sFlow) is
        // structural: ExporterIdentity.Sflow carries only the payload agent address,
        // so no lookup could use the UDP source. What needs pinning here is that an
        // Sflow identity resolves at all, and that its sub-agent id is irrelevant
        // because agent ranges carry no observation-domain pin
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.80.0.0/24":
                        credentials: corp-v3
                """, "test.yaml");

        assertThat(snapshot.agentView().match(sflow("10.80.0.5", 3))).isPresent();
        assertThat(snapshot.agentView().match(sflow("192.168.1.1", 0))).isEmpty();
    }

    private static SnmpProfilesConfig insecureProfiles() {
        return new SnmpProfilesConfig(
                Map.of("corp-v3", TestCredentials.v3(),
                        "legacy-v2c", TestCredentials.v2c(),
                        "legacy-v1", TestCredentials.v1()),
                Map.of("default", PollingProfile.builtInDefault()));
    }

    private static void parseAgents(final String agentsBlock) {
        InventoryLoader.parse(insecureProfiles(), """
                riptide:
                  snmp:
                    agents:
                %s""".formatted(agentsBlock), "test.yaml");
    }

    @Test
    void rangeWiderThanOneAddressWithV2cFailsNamingBothPartiesTheReasonAndBothRemediations() {
        // a credentialed range polls whatever answers from it, so a wide v1/v2c range
        // would offer its cleartext community to any in-range address that sends a flow
        assertThatThrownBy(() -> parseAgents("""
                      "10.90.0.0/16":
                        credentials: legacy-v2c
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.90.0.0/16")
                .hasMessageContaining("legacy-v2c")
                // "speaks v2c", not bare "v2c": that substring also occurs inside the
                // set name, so it would stay green even if the version were dropped
                .hasMessageContaining("speaks v2c")
                .hasMessageContaining("wider than a single address")
                // the reason, which appears in no remediation clause
                .hasMessageContaining("cleartext community")
                // both remediations
                .hasMessageContaining("enumerate")
                .hasMessageContaining("migrate")
                // the message names the reference, never the CredentialSet: interpolating
                // the object would put its community into a startup error and a reload log
                .hasMessageNotContaining("public");
    }

    @Test
    void theWidthBoundaryIsOneAddressNotOneSubnet() {
        // a /31 or /127 is the tightest range that is still more than one address, so it
        // is what separates this rule from a looser "smaller than a subnet" reading
        assertThatThrownBy(() -> parseAgents("""
                      "10.97.0.0/31":
                        credentials: legacy-v2c
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.97.0.0/31");

        assertThatThrownBy(() -> parseAgents("""
                      "2001:db8::/127":
                        credentials: legacy-v2c
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2001:db8::/127");
    }

    @Test
    void disablingAWideRangeDoesNotExemptItFromTheRule() {
        // the decision handed over by story 2.5: a carve-out becomes a live range with a
        // one-character edit, and a disabled range already resolves its references, so a
        // security rule must not be weaker than a naming rule
        assertThatThrownBy(() -> parseAgents("""
                      "10.92.0.0/16":
                        credentials: legacy-v2c
                        enabled: false
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.92.0.0/16")
                .hasMessageContaining("cleartext community");
    }

    @Test
    void wideV1Fails() {
        assertThatThrownBy(() -> parseAgents("""
                      "10.91.0.0/16":
                        credentials: legacy-v1
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.91.0.0/16")
                .hasMessageContaining("legacy-v1")
                .hasMessageContaining("speaks v1");
    }

    @Test
    void singleHostV1AndV2cPassInBothSpellings() {
        // one parse call each: the two spellings land in the same trie slot and would
        // fail as duplicates if declared together (duplicateCoverageFailsNamingBothEntries)
        assertThatCode(() -> parseAgents("""
                      "10.93.0.7":
                        credentials: legacy-v2c
                """)).doesNotThrowAnyException();

        assertThatCode(() -> parseAgents("""
                      "10.93.0.7/32":
                        credentials: legacy-v2c
                """)).doesNotThrowAnyException();

        // FR-9 names the v1 single-host case explicitly; the accept path must not narrow
        // to v2c only
        assertThatCode(() -> parseAgents("""
                      "10.93.0.8":
                        credentials: legacy-v1
                """)).doesNotThrowAnyException();
    }

    @Test
    void anyWidthV3Passes() {
        // separate parse calls: one throwing case must not mask the others
        assertThatCode(() -> parseAgents("""
                      "10.94.0.0/16":
                        credentials: corp-v3
                """)).doesNotThrowAnyException();
        assertThatCode(() -> parseAgents("""
                      "0.0.0.0/0":
                        credentials: corp-v3
                """)).doesNotThrowAnyException();
    }

    @Test
    void wideRangesWithoutACredentialSetPass() {
        assertThatCode(() -> parseAgents("""
                      "10.95.0.0/16":
                        polling: default
                """)).doesNotThrowAnyException();
        // an explicitly empty credentials key reads as absent, like credentials: ~
        assertThatCode(() -> parseAgents("""
                      "10.96.0.0/16":
                        credentials:
                """)).doesNotThrowAnyException();
        assertThatCode(() -> parseAgents("""
                      "10.96.1.0/24":
                        credentials: ~
                """)).doesNotThrowAnyException();
    }

    @Test
    void theRuleAppliesToIpv6InBothDirections() {
        assertThatThrownBy(() -> parseAgents("""
                      "2001:db8::/64":
                        credentials: legacy-v2c
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2001:db8::/64")
                .hasMessageContaining("cleartext community");

        assertThatCode(() -> parseAgents("""
                      "2001:db8::1":
                        credentials: legacy-v2c
                """)).doesNotThrowAnyException();
        assertThatCode(() -> parseAgents("""
                      "2001:db8::1/128":
                        credentials: legacy-v2c
                """)).doesNotThrowAnyException();
        assertThatCode(() -> parseAgents("""
                      "::/0":
                        credentials: corp-v3
                """)).doesNotThrowAnyException();
    }

    @Test
    void interfacePinsParseInBothIfIndexSpellingsAndReachTheEntry() {
        // unquoted is the spelling operators know from the legacy tree and SnakeYAML
        // hands it over as an Integer, so both forms are accepted here deliberately
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core-router:
                      address: 10.30.0.1
                      interfaces:
                        10: { name: eth0, alias: "Uplink to AS64500", high-speed: 10000 }
                        "11": { alias: "Only an alias" }
                    bare-switch:
                      address: 10.30.0.2
                """, "test.yaml");

        final var pins = snapshot.exporterView().match(netflow("10.30.0.1", 0)).orElseThrow().interfaces();
        assertThat(pins).containsOnlyKeys(10, 11);
        assertThat(pins.get(10)).isEqualTo(new InterfacePin("eth0", "Uplink to AS64500", 10_000L));
        // a pin may set one field and leave the rest to the rungs below it
        assertThat(pins.get(11)).isEqualTo(new InterfacePin(null, "Only an alias", null));

        // an exporter without the key carries an empty map, never null
        assertThat(snapshot.exporterView().match(netflow("10.30.0.2", 0)).orElseThrow().interfaces()).isEmpty();
    }

    @Test
    void interfacePinsAreImmutableOnTheBuiltEntry() {
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core-router:
                      address: 10.31.0.1
                      interfaces:
                        1: { name: eth0 }
                """, "test.yaml");

        final var pins = snapshot.exporterView().match(netflow("10.31.0.1", 0)).orElseThrow().interfaces();
        assertThatThrownBy(() -> pins.put(2, new InterfacePin("late", null, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void badInterfacePinShapesFailNamingTheExporterAndTheInterface() {
        // ifIndex 4093 throughout: a distinctive number, so asserting on it cannot be
        // satisfied by boilerplate or by the fixture address the way "0" or "1" would
        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        0: { name: eth0 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-router")
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        -5: { name: eth0 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface -5")
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        eth0: { name: eth0 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-router")
                .hasMessageContaining("eth0");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { nmae: eth0 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface 4093")
                .hasMessageContaining("nmae");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { high-speed: "1000" }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface 4093")
                .hasMessageContaining("not a whole number");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces: nonsense
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core-router")
                // not just the exporter name, which every failure of this fixture carries
                .hasMessageContaining("must be a mapping");
    }

    @Test
    void ifIndexSpellingsThatCouldMeanTwoThingsAreRejected() {
        // "010" would be 10 quoted and 8 unquoted (YAML 1.1 octal), so the quoted form
        // is held to canonical decimal rather than allowed to disagree with its twin
        for (final String key : new String[]{"\"010\"", "\"+8\"", "\" 8 \""}) {
            assertThatThrownBy(() -> parseExporter("""
                          interfaces:
                            %s: { name: eth0 }
                    """.formatted(key)))
                    .as("key %s", key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("plain decimal digits");
        }

        // the same interface declared both ways is a collision SnakeYAML cannot see,
        // and silently keeping the last one would discard an operator's pin
        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { name: unquoted }
                        "4093": { name: quoted }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface 4093 twice");

        // a whole number that no ifIndex can hold says so, rather than claiming it is
        // not a number at all
        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4294967296: { name: eth0 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the ifIndex range");
    }

    @Test
    void pinnedValuesMustBeWrittenAsTheTypeTheyPin() {
        // a pin outranks the walk, so a coerced value would win: `name: on` must not
        // become "true", and a blank must not pin emptiness over what SNMP found
        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { name: 10 }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface 4093")
                .hasMessageContaining("not text");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { alias: [a, b] }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not text");

        assertThatThrownBy(() -> parseExporter("""
                      interfaces:
                        4093: { name: "   " }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank name");

        for (final String speed : new String[]{"0", "-1", "4294967296"}) {
            assertThatThrownBy(() -> parseExporter("""
                          interfaces:
                            4093: { high-speed: %s }
                    """.formatted(speed)))
                    .as("high-speed %s", speed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside 1..");
        }
    }

    @Test
    void twoExportersOnTheSameAddressAndPinFailNamingBoth() {
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    first:
                      address: 10.32.0.1
                      observation-domain: 42
                    second:
                      address: 10.32.0.1
                      observation-domain: 42
                """, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first")
                .hasMessageContaining("second");

        // the same address under different domains is not a collision
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    first:
                      address: 10.33.0.1
                      observation-domain: 42
                    second:
                      address: 10.33.0.1
                      observation-domain: 7
                """, "test.yaml");
        assertThat(snapshot.exporterView().match(netflow("10.33.0.1", 42)))
                .map(ExporterEntry::name).hasValue("first");
        assertThat(snapshot.exporterView().match(netflow("10.33.0.1", 7)))
                .map(ExporterEntry::name).hasValue("second");
    }

    private static void parseExporter(final String body) {
        InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core-router:
                      address: 10.34.0.1
                %s""".formatted(body), "test.yaml");
    }

    @Test
    void portIsPerRangeAndDefaultsTo161() {
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.98.0.0/24":
                        credentials: corp-v3
                        port: 12345
                      "10.98.1.0/24":
                        credentials: corp-v3
                """, "test.yaml");

        assertThat(snapshot.agentView().match(netflow("10.98.0.5", 0)).orElseThrow().port()).isEqualTo(12345);
        assertThat(snapshot.agentView().match(netflow("10.98.1.5", 0)).orElseThrow().port()).isEqualTo(161);

        for (final String bad : new String[]{"\"12345\"", "0", "70000"}) {
            assertThatThrownBy(() -> InventoryLoader.parse(profiles(), """
                    riptide:
                      snmp:
                        agents:
                          "10.98.2.0/24":
                            credentials: corp-v3
                            port: %s
                    """.formatted(bad), "test.yaml"))
                    .as("port %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("10.98.2.0/24");
        }
    }

    @Test
    void addressCoveredByNoRangeYieldsNoAgentEntry() {
        // the loader half of FR-8's not-polled-but-collected rule: no entry means no
        // endpoint can ever be built for it. The runtime half (collected, option-data
        // enriched, never registered) is pinned by
        // SnmpEnricherTest.unmatchedExporterIsCollectedAndOptionEnrichedButNeverPolled
        // against the path that is live until the 2.8 cutover
        final var snapshot = InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """, "test.yaml");

        assertThat(snapshot.agentView().match(netflow("10.99.0.1", 0))).isEmpty();
    }

    /**
     * The #630 collection: an operator hand-writing this file meets every mistake in one
     * boot, and each problem keeps the exact text it produced when it was the only one
     * reported. Six problems, spread across both trees so neither section can be the one
     * that happens to be walked.
     */
    @Test
    void everyProblemInOneFileIsReportedInOneFailure() {
        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        credentials: nope
                      "10.0.1.0/24":
                        credentials: corp-v3
                        port: 70000
                      "not-an-ip": {}
                  exporters:
                    nameless:
                      observation-domain: 1
                    quoted:
                      address: 10.0.2.1
                      observation-domain: "42"
                    typo:
                      address: 10.0.3.1
                      adress: 10.0.3.1
                """, "test.yaml"));

        final List<String> expected = List.of(
                "Agent range '10.0.0.0/24' references credential set 'nope' which is not defined.",
                "Agent range '10.0.1.0/24' has a port 70000 outside 1..65535.",
                "The agent range 'not-an-ip' is not a host address or CIDR prefix.",
                "Exporter 'nameless' has no address — every enrichment entry needs one.",
                "Exporter 'quoted' observation-domain '42' is not a whole number.",
                "The exporter 'typo' has an unknown key 'adress'");

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .as("every problem verbatim, plus the file")
                .contains("test.yaml")
                .contains(expected);
        // the count and the line tally both against a number this test computes, so a
        // seventh fixture problem cannot pass by matching a stale literal. One problem per
        // entry here, so the entry count and the line count are the same number
        assertThat(problemLines(thrown.getMessage())).hasSameSizeAs(expected);
        assertThat(thrown.getMessage()).contains("carries problems in %d entries".formatted(expected.size()));
    }

    /**
     * The bound counts entries, and the remainder is counted rather than dropped. Both
     * halves are asserted numerically: a conditional assertion inside a loop passes
     * identically whether every element satisfied it or none reached it, and a bounded
     * list is exactly where an off-by-one hides (the #562 lesson).
     */
    @Test
    void theReportNamesBoundedEntriesAndCountsTheRest() {
        final int bad = 27;
        final StringBuilder yaml = new StringBuilder("riptide:\n  snmp:\n    agents:\n");
        for (int i = 0; i < bad; i++) {
            yaml.append("      \"10.60.%d.0/24\":\n        credentials: nope\n".formatted(i));
        }

        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), yaml.toString(), "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        final String message = thrown.getMessage();
        assertThat(message).contains("carries problems in %d entries".formatted(bad));
        final List<String> named = problemLines(message);
        assertThat(named).as("the bound itself").hasSize(20);
        final var overflow = java.util.regex.Pattern
                .compile("problems in (\\d+) entries are listed no further").matcher(message);
        assertThat(overflow.find()).as("the remainder is counted, not silently dropped").isTrue();
        assertThat(named.size() + Integer.parseInt(overflow.group(1)))
                .as("named + counted must account for every bad entry")
                .isEqualTo(bad);
    }

    /**
     * One entry, one problem. The value checks are a dependency graph — the cleartext
     * width rule reads what the credential resolve produced — so a check skipped because
     * its input failed must not appear in a report that would then be claiming it ran.
     */
    @Test
    void anEntryWithSeveralFaultsYieldsOnlyTheFirst() {
        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agents:
                      "10.4.0.0/24":
                        enabled: nope
                        port: 70000
                        credentials: alsonope
                """, "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(problemLines(thrown.getMessage())).hasSize(1);
        assertThat(thrown.getMessage())
                .contains("carries problems in 1 entry:")
                .contains("non-boolean enabled value 'nope'")
                .doesNotContain("outside 1..")
                .doesNotContain("credential set 'alsonope'");
    }

    /**
     * The staging cost, stated rather than discovered: pass 2 runs only on a clean pass
     * 1, so a duplicate is never reported alongside a value problem. That is also what
     * keeps the builder from ever being fed a failed entry — the contract
     * {@code PinnedPrefixMatcherTest.duplicateFailurePoisonsTheBuilder} exists to defend.
     */
    @Test
    void aDuplicateSurfacesOnlyOnceTheValueProblemsAreGone() {
        // the colliding pair is VALID, so hoisting the build ahead of the report would
        // reach it and drop the dangling reference on the floor
        final String withBoth = """
                riptide:
                  snmp:
                    agents:
                      "10.0.0.7":
                        credentials: corp-v3
                      "10.0.0.7/32":
                        credentials: corp-v3
                      "10.5.0.0/24":
                        credentials: nope
                """;

        assertThatThrownBy(() -> InventoryLoader.parse(profiles(), withBoth, "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential set 'nope'")
                .hasMessageNotContaining("Ambiguous matcher entries");

        // fix the value problem and the duplicate surfaces, worded exactly as it always was
        assertThatThrownBy(() -> InventoryLoader.parse(profiles(),
                withBoth.replace("credentials: nope", "credentials: corp-v3"), "test.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous matcher entries")
                .hasMessageContaining("10.0.0.7")
                .hasMessageContaining("10.0.0.7/32");
    }

    /** A stray key at two tree levels is reported once each, not one of them twice over. */
    @Test
    void aStrayKeyAtTwoLevelsIsReportedOnceEach() {
        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  snmp:
                    agnets:
                      "10.0.0.0/24": {}
                  exporterz:
                    x:
                      address: 10.0.0.1
                """, "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(problemLines(thrown.getMessage())).hasSize(2);
        assertThat(thrown.getMessage())
                .contains("Unknown key 'agnets' under 'riptide.snmp'")
                .contains("Unknown key 'exporterz' under 'riptide'")
                .as("two levels, two entries")
                .contains("carries problems in 2 entries");
    }

    /**
     * An exporter's interfaces map is its own iteration, so each bad pin recovers on its
     * own: a generated exporter with thirty blank aliases used to cost thirty boots. The
     * exporter's own checks stay one problem — a pin problem here proves the entry got
     * past them.
     */
    @Test
    void everyBadInterfacePinIsItsOwnProblem() {
        final var thrown = catchThrowable(() -> parseExporter("""
                      interfaces:
                        4093: { alias: "  " }
                        4094: { alias: "  " }
                        4095: { nmae: eth0 }
                """));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(problemLines(thrown.getMessage())).hasSize(3);
        assertThat(thrown.getMessage())
                .contains("interface 4093")
                .contains("interface 4094")
                .contains("interface 4095")
                // three lines, but ONE entry: the bound counts exporters, not their pins
                .contains("carries problems in 1 entry:");
    }

    /**
     * The two-spellings rule holds even when the first spelling's value was rejected.
     *
     * <p>The collision is decided from the key, before the value is parsed. Deciding it
     * from what landed in the map let a blank alias on {@code 4095} hide the {@code
     * "4095"} below it — and left {@code interfacePins}' javadoc promising an error that
     * was not raised.</p>
     */
    @Test
    void aDuplicateIfIndexIsReportedEvenWhenTheFirstSpellingsValueFailed() {
        final var thrown = catchThrowable(() -> parseExporter("""
                      interfaces:
                        4095: { alias: "  " }
                        "4095": { name: quoted }
                """));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(problemLines(thrown.getMessage())).hasSize(2);
        assertThat(thrown.getMessage())
                .contains("blank alias")
                .contains("interface 4095 twice");
    }

    /**
     * The bound counts entries, so one pathological entry cannot crowd out the others.
     *
     * <p>Twenty-five blank aliases on a single exporter is one bad entry with
     * twenty-five problems. Bounding problems instead let it fill every named slot while
     * a second exporter — with no address at all, a different mistake needing a
     * different fix — was swallowed into the remainder count. That is the exact failure
     * this collection exists to kill, so it gets its own test.</p>
     */
    @Test
    void onePathologicalEntryDoesNotCrowdOutTheOthers() {
        final int pins = 25;
        final StringBuilder yaml = new StringBuilder("""
                riptide:
                  exporters:
                    generated:
                      address: 10.0.0.1
                      interfaces:
                """);
        for (int i = 1; i <= pins; i++) {
            yaml.append("        %d: { alias: \"  \" }\n".formatted(i));
        }
        yaml.append("    nameless:\n      observation-domain: 1\n");

        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), yaml.toString(), "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .as("the second broken entry is named, not swallowed")
                .contains("Exporter 'nameless' has no address")
                .contains("carries problems in 2 entries")
                // and the pathological entry is capped in place, its remainder counted
                .contains("and %d more in this entry, listed no further".formatted(pins - 5));
    }

    /**
     * A structural failure ends the pass; it must not take the report down with it.
     *
     * <p>{@code agents: notamap} has no entries to walk, so pass 1 cannot continue. The
     * stray key above it was already collected, and dropping it would leave an operator
     * strictly blinder than before problems were collected at all — that key used to be
     * the thing they were told about.</p>
     */
    @Test
    void problemsCollectedBeforeAStructuralFailureAreStillReported() {
        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporterz: {}
                  snmp:
                    agents: notamap
                """, "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .contains("Unknown key 'exporterz' under 'riptide'")
                .contains("'agents' must be a mapping, found String")
                .contains("carries problems in 2 entries");
    }

    /**
     * The report as an operator reads it, asserted whole rather than grepped.
     *
     * <p>Substring assertions pass identically on a report whose header is wrong, whose
     * lines are in a different order, or which lost its indentation. This pins the header
     * sentence, the entry count, tree order (agents before exporters), the per-entry line
     * cap and its remainder line, the two indents, and that the separator is {@code \\n}
     * on every platform.</p>
     */
    @Test
    void theWholeRenderedReportIsWhatAnOperatorReads() {
        final StringBuilder yaml = new StringBuilder("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        credentials: nope
                  exporters:
                    core-router:
                      address: 10.0.0.1
                      interfaces:
                """);
        for (int ifIndex = 1; ifIndex <= 7; ifIndex++) {
            yaml.append("        %d: { alias: \"  \" }\n".formatted(ifIndex));
        }

        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), yaml.toString(), "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).isEqualTo("""
                Inventory file test.yaml carries problems in 2 entries:
                  - Agent range '10.0.0.0/24' references credential set 'nope' which is not defined.
                  - Exporter 'core-router' interface 1 has a blank alias: remove the key to fall back to SNMP.
                  - Exporter 'core-router' interface 2 has a blank alias: remove the key to fall back to SNMP.
                  - Exporter 'core-router' interface 3 has a blank alias: remove the key to fall back to SNMP.
                  - Exporter 'core-router' interface 4 has a blank alias: remove the key to fall back to SNMP.
                  - Exporter 'core-router' interface 5 has a blank alias: remove the key to fall back to SNMP.
                    and 2 more in this entry, listed no further""");
    }

    /**
     * The report is what a startup failure carries, so it must not lose the throwables its
     * lines came from: the reloader hands the exception to SLF4J, and a stack of nothing
     * but loader frames drops what an ifIndex key's {@code NumberFormatException} said.
     */
    @Test
    void theReportCarriesTheThrowablesItsLinesCameFrom() {
        final var thrown = catchThrowable(() -> InventoryLoader.parse(profiles(), """
                riptide:
                  exporters:
                    core-router:
                      address: 10.0.0.1
                      interfaces:
                        4294967296: { name: eth0 }
                """, "test.yaml"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getSuppressed())
                .as("the rejected pin's own throwable")
                .hasSize(1);
        assertThat(thrown.getSuppressed()[0]).hasMessageContaining("outside the ifIndex range");
    }

    /** The report's own problem lines, so a test counts entries instead of grepping prose. */
    private static List<String> problemLines(final String report) {
        return report.lines().filter(line -> line.startsWith("  - ")).toList();
    }

    private static long warnings(final ListAppender<ILoggingEvent> appender, final String range) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(range))
                .count();
    }

    private static ExporterIdentity netflow(final String address, final long domain) {
        try {
            return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), domain);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }

    private static ExporterIdentity sflow(final String agentAddress, final long subAgentId) {
        try {
            return new ExporterIdentity.Sflow(InetAddress.getByName(agentAddress), subAgentId);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(agentAddress, e);
        }
    }
}
