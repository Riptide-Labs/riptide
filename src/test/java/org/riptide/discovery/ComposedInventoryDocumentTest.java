/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.config.FileWatchTrigger;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryDocument;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.inventory.TestCredentials;
import org.riptide.testsupport.LogCapture;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposedInventoryDocumentTest {

    private static final String DEVICES = """
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
            """;

    /** A file document answering fixed text, standing in for riptide.inventory.file. */
    private record FixedFile(String text) implements InventoryDocument {
        @Override
        public String name() {
            return "inventory.yaml";
        }
    }

    private static ComposedInventoryDocument composed(final String fileText, final String json) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(
                new FixedFile(fileText),
                new ServiceDiscoverySource(() -> json.getBytes(StandardCharsets.UTF_8), () -> "the endpoint"),
                () -> "the endpoint",
                config,
                new MetricRegistry());
    }

    @Test
    void theAgentsTreeFromTheFileSurvivesAndExportersComeFromDiscovery() {
        final String text = composed("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/8":
                        credentials: corp-v3
                """, DEVICES).text();

        assertThat(text).contains("10.0.0.0/8").contains("corp-v3");
        assertThat(text).contains("firewall-01").contains("10.0.0.1");
    }

    @Test
    void anExportersTreeInTheFileFailsNamingBothOwners() {
        assertThatThrownBy(() -> composed("""
                riptide:
                  exporters:
                    hand-written:
                      address: 10.9.9.9
                """, DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.url")
                .hasMessageContaining("exporters")
                .hasMessageContaining("inventory.yaml");
    }

    /*
     * The file is parsed by the loader's rules, not a copy of them. Each case below is one guard
     * the loader applies with discovery off; a merge with SnakeYAML options of its own loosened
     * every one of them with discovery on.
     */

    @Test
    void aDuplicatedAgentRangeIsRefusedRatherThanCollapsedToTheLastEntry() {
        assertThatThrownBy(() -> composed("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/24":
                        credentials: a
                      "10.0.0.0/24":
                        credentials: b
                """, DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.yaml")
                .hasMessageContaining("duplicate key");
    }

    @Test
    void malformedYamlFailsNamingTheFile() {
        assertThatThrownBy(() -> composed("riptide: [unclosed", DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid YAML")
                .hasMessageContaining("inventory.yaml");
    }

    @Test
    void aFileWhoseRootIsNotAMappingIsRefusedRatherThanDropped() {
        assertThatThrownBy(() -> composed("""
                - riptide
                - snmp
                """, DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.yaml")
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void aRiptideTreeThatIsNotAMappingIsRefusedRatherThanDropped() {
        assertThatThrownBy(() -> composed("riptide: agents-go-here", DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.yaml")
                .hasMessageContaining("'riptide' must be a mapping");
    }

    @Test
    void aNonStringKeyAtTheFileRootIsRefused() {
        // unquoted `on` is a YAML 1.1 boolean, so it arrives as the key true
        assertThatThrownBy(() -> composed("""
                on: 1
                riptide:
                  snmp: {}
                """, DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.yaml")
                // "the document root", not "the file root": with discovery on the root being walked
                // belongs to a composed document. This is the one phrase that could not be
                // dispatched per source, because it is a literal inside a problem line (#803)
                .hasMessageContaining("Key 'true' under the document root is not a string");
    }

    /**
     * What every sentence naming this document says, which is never "file": it is a file composed
     * with an endpoint, and an operator told to fix a file goes to the half that may be fine. The
     * reloader used to decide this with a conditional of its own, and said "Inventory document"
     * while its sibling sentences said "Inventory source" (#803).
     */
    @Test
    void theComposedDocumentIsNamedASourceAndNeverAFile() {
        final var composed = composed("riptide:\n", DEVICES);

        assertThat(composed.subject())
                .startsWith("Inventory source ")
                .contains(composed.name())
                .doesNotContain("Inventory file")
                .doesNotContain("Inventory document");
        assertThat(composed.noun()).isEqualTo("inventory source");
    }

    @Test
    void itPrescribesARemedyThatExistsOnAnEndpoint() {
        assertThat(composed("riptide:\n", DEVICES).partialReadAdvice())
                .as("no mv fixes a response that was read short")
                .doesNotContain("mv")
                .doesNotContain("file")
                .contains("the next poll");
    }

    @Test
    void anAbsentInventoryFileIsFineBecauseDiscoveryOwnsExportersAlone() {
        final String text = composed(null, DEVICES).text();

        assertThat(text).contains("firewall-01");
    }

    @Test
    void theSameInputsRenderByteIdenticalDocuments() {
        assertThat(composed(null, DEVICES).text()).isEqualTo(composed(null, DEVICES).text());
    }

    @Test
    void anEmptyDocumentIsRefusedRatherThanEmptyingTheTree() {
        assertThatThrownBy(() -> composed(null, "[]").text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
    }

    /**
     * Only the skipped gauge lives here, and only it is render-time. The target gauge moved to
     * {@link DiscoveryTargetsGauge} and reads the published inventory, because a value set during
     * composition describes a candidate that may still be refused (#807). Skipped stays render-time
     * on purpose: a device the endpoint keeps offering with no usable address is worth seeing
     * precisely while the candidate around it is being refused.
     */
    @Test
    void theSkippedGaugeReportsWhatTheLastRenderDropped() {
        final MetricRegistry metrics = new MetricRegistry();
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        final ComposedInventoryDocument document = new ComposedInventoryDocument(
                new FixedFile(null),
                new ServiceDiscoverySource(() -> """
                        [{"targets":["a"],"labels":{"__meta_netbox_name":"a",
                          "__meta_netbox_primary_ip4":"10.0.0.1"}},
                         {"targets":["b"],"labels":{"__meta_netbox_name":"b"}}]
                        """.getBytes(StandardCharsets.UTF_8),
                        () -> "the endpoint"),
                () -> "the endpoint",
                config,
                metrics);

        document.text();

        assertThat(metrics.getGauges().get("discovery.skipped").getValue()).isEqualTo(1);
        assertThat(metrics.getGauges())
                .as("the target gauge is not this object's to register; it needs the serving inventory")
                .doesNotContainKey("discovery.targets");
    }

    @Test
    void anUnreachableEndpointFailsNamingIt() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        final ComposedInventoryDocument document = new ComposedInventoryDocument(
                new FixedFile(null),
                () -> {
                    throw new IOException("connection refused");
                },
                () -> "the endpoint",
                config,
                new MetricRegistry());

        assertThatThrownBy(document::text)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("connection refused");
    }

    private static ComposedInventoryDocument composed(final String fileText,
                                                      final ServiceDiscoverySource.Fetcher fetcher) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(new FixedFile(fileText), new ServiceDiscoverySource(fetcher, () -> "the endpoint"), () -> "the endpoint", config,
                new MetricRegistry());
    }

    private static final String AGENTS = """
            riptide:
              snmp:
                agents:
                  "10.0.0.0/8":
                    credentials: corp-v3
            """;

    /*
     * Boot degrades on a failed fetch and on nothing else. Every later reader stays strict.
     */

    @Test
    void aFetchFailureAtBootServesTheFileWithNoExportersTree() {
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new IOException("connection refused");
        });

        final String text = document.bootText();

        assertThat(text).contains("10.0.0.0/8").contains("corp-v3");
        // no key at all, not an empty mapping: `exporters: {}` would declare the tree deliberately
        // empty, and the regression guard honours that declaration
        assertThat(text).doesNotContain("exporters");
        assertThat(document.degradedAtBoot()).isTrue();
    }

    /**
     * The second line of defence behind the method split: the degraded document omits the exporters
     * key, so the regression guard would refuse it over a populated inventory if it ever reached a
     * reload, instead of honouring an explicit empty tree.
     */
    @Test
    void aDegradedDocumentWouldBeRefusedByTheRegressionGuardOverAPopulatedInventory() {
        final SnmpProfilesConfig profiles = new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());
        final InventorySnapshot populated = InventoryLoader.parse(profiles, composed(AGENTS, DEVICES).text(), "full");
        final InventorySnapshot degraded = InventoryLoader.parse(profiles, composed(AGENTS, () -> {
            throw new IOException("connection refused");
        }).bootText(), "degraded");

        assertThat(populated.exporterCount()).isEqualTo(1);
        assertThat(degraded.isRegressiveOver(populated)).isTrue();
    }

    @Test
    void aReachableEndpointAtBootServesWhatEveryLaterReadComposes() {
        final ComposedInventoryDocument document = composed(AGENTS, DEVICES);

        assertThat(document.bootText()).isEqualTo(document.text());
        assertThat(document.degradedAtBoot()).isFalse();
    }

    @Test
    void aContentFailureAtBootStillFailsBoot() {
        final ComposedInventoryDocument document = composed(AGENTS, "[]");

        assertThatThrownBy(document::bootText)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
        assertThat(document.degradedAtBoot()).isFalse();
    }

    @Test
    void anExportersTreeInTheFileStillFailsBootWithTheEndpointDown() {
        final ComposedInventoryDocument document = composed("""
                riptide:
                  exporters:
                    hand-written:
                      address: 10.9.9.9
                """, () -> {
                    throw new IOException("connection refused");
                });

        final ListAppender<ILoggingEvent> captured = captureDocumentLog();
        try {
            assertThatThrownBy(document::bootText)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("riptide.discovery.url");
        } finally {
            releaseDocumentLog(captured);
        }
        // the compose runs before the WARN, so the operator does not read "serving the inventory
        // file's trees" immediately above a startup failure that serves nothing at all
        assertThat(captured.list)
                .as("nothing is promised by a boot that is about to fail")
                .isEmpty();
    }

    /**
     * The endpoint is named once. Every message {@code BoundedHttpRead} raises already opens with
     * the same description this sentence uses, so quoting it whole read "could not reach X (X
     * answered 404)".
     */
    @Test
    void theDegradedBootWarningNamesTheEndpointOnce() {
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new FileNotFoundException("the endpoint answered 404");
        });

        final ListAppender<ILoggingEvent> captured = captureDocumentLog();
        try {
            document.bootText();
        } finally {
            releaseDocumentLog(captured);
        }

        assertThat(captured.list).singleElement().satisfies(event -> {
            final String message = event.getFormattedMessage();
            assertThat(message).startsWith("Boot could not reach the endpoint: answered 404.");
            assertThat(message.split("the endpoint", -1).length - 1)
                    .as("the endpoint is named once, in: %s", message)
                    .isEqualTo(1);
        });
    }

    /** A JDK-raised failure names no endpoint of its own, so it is quoted as it is. */
    @Test
    void aRefusedConnectionKeepsItsOwnWordingBehindTheEndpointsName() {
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new IOException("Connection refused");
        });

        final ListAppender<ILoggingEvent> captured = captureDocumentLog();
        try {
            document.bootText();
        } finally {
            releaseDocumentLog(captured);
        }

        assertThat(captured.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .startsWith("Boot could not reach the endpoint: Connection refused."));
    }

    /**
     * A degraded boot promises only what the configuration can deliver.
     *
     * <p>With the watcher disabled nothing re-reads the endpoint, so a sentence naming a retry
     * cadence and a staleness gauge would send an operator to wait for a poll that never runs and
     * to watch a metric that is never registered. The clause was written per-interval from the
     * start; until #808 nothing tested either branch of it.</p>
     */
    @Test
    void withTheWatcherDisabledTheBootWarningNamesTheRestartAndNoGauge() {
        final var captured = captureDocumentLog();
        try {
            degradedBootWith(Duration.ZERO);

            assertThat(captured.list).singleElement().satisfies(event -> {
                final String message = event.getFormattedMessage();
                assertThat(message)
                        .contains("stay missing until a restart")
                        .contains("riptide.discovery.interval");
                assertThat(message)
                        .as("no gauge is registered in this configuration, so promising one misleads")
                        .doesNotContain("inventory.reload.stale reads 1")
                        .doesNotContain("A reload retries every");
            });
        } finally {
            releaseDocumentLog(captured);
        }
    }

    @Test
    void withAWorkingScheduleTheBootWarningNamesTheIntervalThatWillRetry() {
        final var captured = captureDocumentLog();
        try {
            degradedBootWith(Duration.ofSeconds(30));

            assertThat(captured.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .as("the twin: a test covering only the disabled branch cannot tell a working "
                            + "conditional from one that always takes it")
                    .contains("A reload retries every PT30S")
                    .contains("inventory.reload.stale")
                    .doesNotContain("until a restart"));
        } finally {
            releaseDocumentLog(captured);
        }
    }

    /** A boot that cannot reach the endpoint, under a given poll interval. */
    private static void degradedBootWith(final Duration interval) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        config.setInterval(interval);
        new ComposedInventoryDocument(new FixedFile(AGENTS),
                new ServiceDiscoverySource(() -> {
                    throw new IOException("connection refused");
                }, () -> "the endpoint"),
                () -> "the endpoint", config, new MetricRegistry())
                .bootText();
    }

    private static ListAppender<ILoggingEvent> captureDocumentLog() {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ComposedInventoryDocument.class);
        final ListAppender<ILoggingEvent> appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        return appender;
    }

    private static void releaseDocumentLog(final ListAppender<ILoggingEvent> appender) {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ComposedInventoryDocument.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void aFetchFailureAfterBootStillThrowsForEveryLaterReader() {
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new IOException("connection refused");
        });
        document.bootText();

        // text() is what both credential-rotation rebuilds read, fetch() what the watcher polls
        assertThatThrownBy(document::text)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
        assertThatThrownBy(document::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
    }

    /**
     * What the method split is worth in production, and the one thing nothing pinned: that
     * {@code Inventory.rebuildAndSwap} reads {@code text()} and not {@code bootText()}. The
     * existing rotation test runs against a live endpoint, where the two agree, and a stub's
     * {@code bootText()} delegates to {@code text()} — so the swap survived the whole suite.
     *
     * <p>The state it costs: a degraded boot (the endpoint was down, so zero exporters are
     * serving), {@code riptide.discovery.interval} at zero so no watcher exists to heal it, and an
     * operator rotating a credential. Reading {@code bootText()} there republishes the file-only
     * document, which the regression guard waves through because it matches what is already
     * serving. The rotation is then recorded as fully applied and every exporter name stays
     * missing until a restart, with nothing left saying so. Reading {@code text()} throws, the
     * config reloader parks the profiles as pending, and its WARN tells the operator the rotation
     * is not serving yet.</p>
     */
    @Test
    void aRotationDuringADegradedBootIsRefusedRatherThanPublishedWithoutTheExporters() {
        final SnmpProfilesConfig booted = new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new IOException("connection refused");
        });
        final Inventory inventory = new Inventory(booted, document);
        inventory.load();
        assertThat(inventory.snapshot().agentCount()).as("boot degraded to the file's trees").isEqualTo(1);
        assertThat(inventory.snapshot().exporterCount()).as("with no exporters").isZero();

        final SnmpProfilesConfig rotated = new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());

        assertThatThrownBy(() -> inventory.rebuildAndSwap(rotated))
                .as("the rebuild reads the strict text(), so it fails rather than degrading again")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
        assertThat(inventory.profiles())
                .as("and the rotation is NOT recorded as serving")
                .isSameAs(booted);
    }

    private static ComposedInventoryDocument failingWith(final IOException failure) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(
                new FixedFile(null),
                new ServiceDiscoverySource(() -> {
                    throw failure;
                }, () -> "the endpoint"),
                () -> "the endpoint",
                config,
                new MetricRegistry());
    }

    /**
     * DiscoveryClient throws FileNotFoundException for a 404 and nothing else. The watcher must see
     * that as absence, which skips and keeps serving, not as a failure it counts every cycle.
     */
    @Test
    void aNotFoundEndpointIsAbsenceForTheWatcherRatherThanAFailure() throws IOException {
        assertThat(failingWith(new FileNotFoundException("http://netbox/devices")).fetch())
                .isInstanceOf(FileWatchTrigger.Fetch.Absent.class);
    }

    @Test
    void anyOtherFetchFailureReachesTheWatcherAsAThrow() {
        assertThatThrownBy(() -> failingWith(new IOException("connection refused")).fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
    }

    /**
     * A stand-in for {@code FileInventoryDocument} with an unreadable file, wrapping the read's own
     * {@code IOException} exactly as it does. The two causes below are the ones a real filesystem
     * raises: {@link NoSuchFileException} for a file that is not there, and
     * {@link AccessDeniedException} for one that is there and unreadable. Both are a
     * {@code FileSystemException} and neither is a {@code FileNotFoundException}, which is why
     * matching that alone left a deleted inventory file counted as a failure on every poll forever.
     *
     * <p>The permission denial is pinned here rather than against a real file because removing read
     * permission does not deny a process running as root, which is how this suite runs in a
     * container. {@code InventoryFileReloaderTest} drives both halves through the real
     * {@code FileInventoryDocument} on a real filesystem.</p>
     */
    private record UnreadableFile(IOException cause) implements InventoryDocument {
        @Override
        public String text() {
            throw new IllegalStateException(
                    "Inventory file /etc/riptide/inventory.yaml is not readable: " + this.cause.getMessage(),
                    this.cause);
        }

        @Override
        public String name() {
            return "/etc/riptide/inventory.yaml";
        }
    }

    private static ComposedInventoryDocument withFile(final InventoryDocument file) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(file,
                new ServiceDiscoverySource(() -> DEVICES.getBytes(StandardCharsets.UTF_8), () -> "the endpoint"),
                () -> "the endpoint", config, new MetricRegistry());
    }

    @Test
    void aMissingInventoryFileIsAbsenceForTheWatcherJustAsItIsWithDiscoveryOff() throws IOException {
        assertThat(withFile(new UnreadableFile(new NoSuchFileException("/etc/riptide/inventory.yaml"))).fetch())
                .isInstanceOf(FileWatchTrigger.Fetch.Absent.class);
    }

    /** A file that is there and unreadable is a real failure and must stay one. */
    @Test
    void anInventoryFileThatIsThereButUnreadableStaysAThrow() {
        assertThatThrownBy(() ->
                withFile(new UnreadableFile(new AccessDeniedException("/etc/riptide/inventory.yaml"))).fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not readable");
    }

    /**
     * The endpoint's flag is the endpoint's: {@code InventoryFileReloader} latches
     * {@code inventory.reload.stale} from it, and a missing file must not latch it, because the
     * discovery-off path does not either.
     */
    @Test
    void aMissingInventoryFileDoesNotLatchTheEndpointsOwnAbsenceFlag() throws IOException {
        final ComposedInventoryDocument document =
                withFile(new UnreadableFile(new NoSuchFileException("/etc/riptide/inventory.yaml")));

        document.fetch();

        assertThat(document.endpointAbsent()).isFalse();
    }

    /**
     * Tasks 1.1, 1.3 and 1.4 of the empty-declaration change. The loader tells a tree the operator
     * declared empty on purpose from one that is simply missing, and only the first permits a
     * publish that drops it. Composition must not quietly turn the first into the second.
     */
    private static final String RIPTIDE_DECLARED_EMPTY = """
            riptide: {}
            """;

    private static final String AGENTS_DECLARED_EMPTY = """
            riptide:
              snmp:
                agents: {}
            """;

    private static final String POPULATED_AGENT_TREE = """
            riptide:
              snmp:
                agents:
                  "10.0.0.0/8":
                    credentials: corp-v3
            """;

    private static SnmpProfilesConfig testProfiles() {
        return new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());
    }

    /** A snapshot with one agent range, to measure a drop against. */
    private static InventorySnapshot populatedInventory() {
        return InventoryLoader.parse(testProfiles(), composed(POPULATED_AGENT_TREE, DEVICES).text(), "populated");
    }

    @Test
    void aBroadlyDeclaredEmptyTreeStaysDeclaredThroughComposition() {
        final String composedText = composed(RIPTIDE_DECLARED_EMPTY, DEVICES).text();
        final InventorySnapshot candidate = InventoryLoader.parse(testProfiles(), composedText, "decommission");

        assertThat(candidate.agentCount()).isZero();
        assertThat(candidate.isRegressiveOver(populatedInventory()))
                .as("`riptide: {}` is the documented broad decommission; composition must not revoke it")
                .isFalse();
    }

    @Test
    void aNarrowlyDeclaredEmptyTreeStaysDeclaredThroughComposition() {
        final String composedText = composed(AGENTS_DECLARED_EMPTY, DEVICES).text();
        final InventorySnapshot candidate = InventoryLoader.parse(testProfiles(), composedText, "decommission");

        assertThat(candidate.agentCount()).isZero();
        assertThat(candidate.isRegressiveOver(populatedInventory()))
                .as("the narrow form already survived; a fix for the broad one must not break it")
                .isFalse();
    }

    @Test
    void anAbsentAgentTreeIsStillNotADeclaration() {
        final InventorySnapshot withAgents = InventoryLoader.parse(
                testProfiles(), composed(POPULATED_AGENT_TREE, DEVICES).text(), "present");
        final InventorySnapshot noTreeAtAll =
                InventoryLoader.parse(testProfiles(), composed(null, DEVICES).text(), "absent");

        assertThat(withAgents.agentCount()).isEqualTo(1);
        assertThat(noTreeAtAll.isRegressiveOver(withAgents))
                .as("a file that declares no agent tree must not read as a deliberate decommission")
                .isTrue();
    }

    @Test
    void aDegradedCompositionCarriesTheBroadDeclarationThroughUnchanged() {
        final ComposedInventoryDocument document = composed(RIPTIDE_DECLARED_EMPTY, () -> {
            throw new IOException("connection refused");
        });

        final String text = document.bootText();
        final InventorySnapshot candidate = InventoryLoader.parse(testProfiles(), text, "degraded");

        assertThat(document.degradedAtBoot()).isTrue();
        assertThat(text)
                .as("a degraded document has no exporters key at all, not an empty one")
                .doesNotContain("exporters");
        assertThat(candidate.isRegressiveOver(populatedInventory()))
                .as("an unreachable endpoint must not change the meaning of what the operator wrote")
                .isFalse();
    }
}
