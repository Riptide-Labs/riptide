/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.discovery.ComposedInventoryDocument;
import org.riptide.discovery.DiscoveryConfig;
import org.riptide.discovery.DiscoveryEndpoints;
import org.riptide.discovery.ServiceDiscoverySource;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.inventory.TestCredentials;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.riptide.snmp.SnmpPollConfig;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.testsupport.LogCapture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reloader's whole contract, driven by manual {@code poll()} calls: the
 * scheduled interval is far in the future, mirroring {@code ConfigFileReloaderTest}.
 */
class InventoryFileReloaderTest {

    @TempDir
    Path tempDir;

    private Path file;
    private SnmpProfilesConfig profiles;
    private Inventory inventory;
    private CountingPoller poller;

    private static final class NoSnmp implements org.riptide.snmp.SnmpService {
        @Override
        public java.util.Optional<org.riptide.snmp.IfInfo> getIfInfo(
                final org.riptide.snmp.SnmpEndpoint endpoint, final int ifIndex) {
            return java.util.Optional.empty();
        }

        @Override
        public InterfaceTable walkInterfaces(final org.riptide.snmp.SnmpEndpoint endpoint) {
            return new InterfaceTable(java.util.Map.of(), false);
        }

        @Override
        public org.riptide.snmp.collect.CollectedTable collect(final org.riptide.snmp.SnmpEndpoint endpoint,
                final org.riptide.snmp.collect.CollectionDefinition definition, final java.time.Duration budget) {
            return new org.riptide.snmp.collect.CollectedTable(java.util.Map.of(), false);
        }
    }
    /** Counts refreshes so the reload trigger is observable; the sweep itself is a no-op here. */
    private static final class CountingPoller extends InterfaceSnapshotPoller {
        private int refreshes;
        /** #559: the refresh is made, then fails — the aftermath is what is under test. */
        private boolean throwOnRefresh;

        private CountingPoller(final Inventory inventory, final MetricRegistry metrics) {
            super(new NoSnmp(), new SnmpPollConfig(), metrics, inventory);
        }

        @Override
        public void refreshRegistrations() {
            this.refreshes++;
            if (this.throwOnRefresh) {
                throw new IllegalStateException("poller is having a day");
            }
            super.refreshRegistrations();
        }
    }

    private MetricRegistry metrics;
    private InventoryFileReloader reloader;

    @BeforeEach
    void setUp() throws IOException {
        this.file = this.tempDir.resolve("inventory.yaml");
        this.profiles = new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());

        final InventoryConfig inventoryConfig = new InventoryConfig();
        inventoryConfig.setFile(this.file);
        final ConfigReloadProperties properties = new ConfigReloadProperties();
        properties.setReloadInterval(Duration.ofHours(1));

        // a set-but-missing file fails boot by design, so boot always sees a file
        write("riptide: {}");
        this.inventory = new Inventory(this.profiles, new FileInventoryDocument(inventoryConfig));
        this.inventory.load();
        this.metrics = new MetricRegistry();
        // a poller with no scheduler and nothing registered: these tests exercise the
        // reload trigger, and the refresh half has its own tests in the poller suite
        this.poller = new CountingPoller(this.inventory, this.metrics);
        this.reloader = new InventoryFileReloader(properties, inventoryConfig, this.inventory,
                this.poller, this.metrics, Optional.empty());
        this.reloader.start();
    }

    @AfterEach
    void tearDown() {
        this.reloader.stop();
        // the poller starts a 1 Hz scheduler in its constructor, so one per test method
        // survived the run without this
        this.poller.stop();
    }

    @Test
    void contentChangeIsPickedUpAndServed() throws Exception {
        assertThat(this.inventory.snapshot().agentView().match(netflow("10.20.5.5"))).isEmpty();

        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();

        final var match = this.inventory.snapshot().agentView().match(netflow("10.20.5.5"));
        assertThat(match).isPresent();
        assertThat(match.get().credentials()).isSameAs(this.profiles.credentials().get("corp-v3"));
        assertThat(successes()).isEqualTo(1);
        assertThat(stale()).isZero();
    }

    /**
     * AD-6's ordering on the inventory watcher's side. The config reloader's half has its
     * own test; this half had none, so deleting the refresh call here left the suite green
     * while a carve-out written to the inventory file stopped reaching a polled agent.
     */
    @Test
    void aCommittedReloadRefreshesThePollerAndARefusedOneDoesNot() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();
        assertThat(this.poller.refreshes).as("a committed reload refreshes").isEqualTo(1);

        // parses to nothing over a populated inventory: refused, so nothing was
        // republished and there is nothing to re-resolve against
        write("---\n");
        this.reloader.poll();
        assertThat(this.poller.refreshes).as("a refused reload refreshes nothing").isEqualTo(1);

        // unchanged content is not recommitted either, so it must not sweep the fleet
        this.reloader.poll();
        assertThat(this.poller.refreshes).isEqualTo(1);
    }

    @Test
    void invalidContentKeepsTheLastGoodSnapshotServing() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();
        assertThat(successes()).isEqualTo(1);

        final var appender = capture(InventoryFileReloader.class);
        try {
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            credentials: nope
                    """);
            this.reloader.poll();
        } finally {
            release(InventoryFileReloader.class, appender);
        }

        // swap rejected: the previous snapshot serves, failure counted, staleness latched
        assertThat(this.inventory.snapshot().agentView().match(netflow("10.20.5.5"))).isPresent();
        assertThat(failures()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
        // and the operator is told WHY, which reloading.md promises ("logs a warning
        // naming the problem"). The counter and the gauge both move without a word being
        // said, so emptying this reloader's failure sentence left the whole suite green
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).hasToString("WARN");
            assertThat(event.getFormattedMessage())
                    .contains("Inventory reload failed, keeping the last good inventory")
                    .contains("nope");
        });
    }

    /**
     * #630 on the reload path: the loader now fails once with every bad entry, and that
     * report has to arrive as ONE warning. A per-problem log would interleave with other
     * threads and stop being one readable failure. Nothing starts throwing either — the
     * last good snapshot keeps serving, exactly as for a single problem.
     */
    @Test
    void aFileWithSeveralProblemsArrivesAsOneWarningCarryingThemAll() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();
        assertThat(successes()).isEqualTo(1);

        final var appender = capture(InventoryFileReloader.class);
        try {
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            credentials: nope
                          "10.21.0.0/16":
                            polling: warp-speed
                          "10.22.0.0/16":
                            port: 70000
                    """);
            this.reloader.poll();
        } finally {
            release(InventoryFileReloader.class, appender);
        }

        assertThat(this.inventory.snapshot().agentView().match(netflow("10.20.5.5")))
                .as("the last good snapshot keeps serving").isPresent();
        assertThat(failures()).isEqualTo(1);
        assertThat(warnings(appender)).hasSize(1);
        assertThat(warnings(appender).get(0))
                .contains("keeping the last good inventory")
                .contains("carries problems in 3 entries")
                .contains("nope")
                .contains("warp-speed")
                .contains("70000");
    }

    /**
     * The two skip sentences describe different conditions with different remediations —
     * "the file is gone" versus "the file is truncated" — and they are adjacent
     * same-typed arguments where this reloader builds its messages. Swapping them
     * compiles and, until this test, changed no assertion anywhere.
     */
    @Test
    void theSkipSentencesNameTheConditionTheyActuallyDescribe() throws Exception {
        final var appender = capture(InventoryFileReloader.class);
        try {
            Files.delete(this.file);
            this.reloader.poll();
            assertThat(warnings(appender)).containsExactly(missingWarning());

            // truncated, not deleted: the other sentence, and only once across five polls
            write("   \n\t\n");
            for (int poll = 0; poll < 5; poll++) {
                this.reloader.poll();
            }
            assertThat(warnings(appender))
                    .as("five truncated polls, one warning")
                    .containsExactly(missingWarning(), blankWarning());
        } finally {
            release(InventoryFileReloader.class, appender);
        }
        assertThat(failures()).as("neither shape is a reload failure").isZero();
    }

    private String missingWarning() {
        return ("Inventory file %s is missing: skipping reload cycles until it reappears "
                + "(deletion and atomic replacement are indistinguishable; keeping the running inventory)")
                .formatted(this.file);
    }

    private String blankWarning() {
        return ("Inventory file %s is empty or whitespace-only: skipping reload cycle "
                + "(truncate-write race or intentional; keeping the running inventory)").formatted(this.file);
    }

    private static java.util.List<String> warnings(
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> "WARN".equals(event.getLevel().toString()))
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void contentThatParsesToNothingDoesNotWipeAPopulatedInventory() throws Exception {
        // a non-atomic writer can flush a lone '---' or a header comment: non-blank, so
        // the blank guard passes, but it parses to zero entries. Committing that would
        // stop every walk and blank enrichment until the writer finished
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();
        assertThat(successes()).isEqualTo(1);

        write("---\n");
        this.reloader.poll();

        // the populated inventory keeps serving, and this is a refusal, not a failure:
        // deleting the file already behaves this way, so it is the same rule
        assertThat(this.inventory.snapshot().agentView().match(netflow("10.20.5.5"))).isPresent();
        assertThat(successes()).isEqualTo(1);
        assertThat(failures()).isZero();
    }

    /**
     * The per-tree torn-write guard (#535), at the watcher level: the pre-check and the
     * monitor-held guard both sit on this path, and deleting either used to leave this
     * suite green because only the whole-file case was tested.
     */
    @Test
    void aTornOneTreeFileIsRefusedAndTheFullWriteHeals() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                  exporters:
                    core:
                      address: 10.20.0.1
                """);
        this.reloader.poll();
        assertThat(this.inventory.snapshot().agentCount()).isEqualTo(1);
        assertThat(this.inventory.snapshot().exporterCount()).isEqualTo(1);

        // a torn read: exporters flushed, agents truncated. Refusal, not failure — the
        // same rule as deletion, and the operator remediation lives in the warn
        write("""
                riptide:
                  exporters:
                    core:
                      address: 10.20.0.1
                """);
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(InventoryFileReloader.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            this.reloader.poll();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(this.inventory.snapshot().agentCount())
                .as("the polled fleet must survive a torn read").isEqualTo(1);
        assertThat(failures()).isZero();
        // like the failure path: the file on disk does not match what is serving, and the
        // gauge must say so NOW — the first version left it at 0 until the next cycle's
        // unchanged-content recompute, a one-interval blink the docs never described
        assertThat(stale())
                .as("a refusal latches staleness immediately, not one poll later")
                .isEqualTo(1);
        // RENDERED, not the format string: the braces in "agents: {}" are SLF4J
        // placeholders unless escaped, and the unescaped form ate its own arguments —
        // the message teaching the idiom printed garbage counts (CodeQL 150/151)
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("agents: {}")
                .contains("1 -> 0 agent range(s)"));

        // the writer finishes; the changed content re-parses and publishes
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                      "10.30.0.0/16":
                        credentials: corp-v3
                  exporters:
                    core:
                      address: 10.20.0.1
                """);
        this.reloader.poll();
        assertThat(this.inventory.snapshot().agentCount()).isEqualTo(2);
    }

    /** The authored decommission: an explicit empty mapping publishes through poll(). */
    @Test
    void anExplicitlyEmptyTreeDecommissionsThroughTheWatcher() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                  exporters:
                    core:
                      address: 10.20.0.1
                """);
        this.reloader.poll();
        assertThat(this.inventory.snapshot().agentCount()).isEqualTo(1);

        write("""
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    core:
                      address: 10.20.0.1
                """);
        this.reloader.poll();
        assertThat(this.inventory.snapshot().agentCount()).isZero();
        assertThat(this.inventory.snapshot().exporterCount()).isEqualTo(1);
        assertThat(failures()).isZero();
    }

    @Test
    void anEmptyInventoryStillLoadsWhenNothingIsRunning() throws Exception {
        // the refusal is only about not wiping a populated inventory; an empty
        // candidate over an already-empty one is a normal, committed reload
        write("riptide: {}\n");
        this.reloader.poll();

        assertThat(successes()).isEqualTo(1);
        assertThat(failures()).isZero();
    }

    @Test
    void sameBadContentIsAttemptedOnlyOnce() throws Exception {
        write("not: [valid");
        this.reloader.poll();
        this.reloader.poll();
        this.reloader.poll();

        assertThat(failures()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
    }

    @Test
    void fixingTheFileRecoversAndClearsStaleness() throws Exception {
        write("riptide: [broken");
        this.reloader.poll();
        assertThat(stale()).isEqualTo(1);

        write("""
                riptide:
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        this.reloader.poll();

        assertThat(this.inventory.snapshot().exporterView().match(netflow("10.0.0.1"))).isPresent();
        assertThat(successes()).isEqualTo(1);
        assertThat(stale()).isZero();
    }

    @Test
    void emptyFileSkipsWithoutFailure() throws Exception {
        // a shell '>' redirect truncates before writing; never commit on empty
        write("");
        this.reloader.poll();

        assertThat(failures()).isZero();
        assertThat(successes()).isZero();
        assertThat(stale()).isZero();
    }

    @Test
    void deletionAfterACommitKeepsServingWithoutFailure() throws Exception {
        write("""
                riptide:
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        this.reloader.poll();
        Files.delete(this.file);
        this.reloader.poll();

        assertThat(this.inventory.snapshot().exporterView().match(netflow("10.0.0.1"))).isPresent();
        assertThat(failures()).isZero();
    }

    @Test
    void bootContentIsNotRecommitted() {
        // the hashes are seeded from the boot-loaded file, so the first cycle
        // does not spuriously re-swap an unchanged inventory
        this.reloader.poll();

        assertThat(successes()).isZero();
        assertThat(failures()).isZero();
        assertThat(stale()).isZero();
    }

    @Test
    void unchangedContentIsCommittedOnlyOnce() throws Exception {
        write("""
                riptide:
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        this.reloader.poll();
        this.reloader.poll();
        this.reloader.poll();

        assertThat(successes()).isEqualTo(1);
    }

    @Test
    void missingFileReappearingIsPickedUp() throws Exception {
        write("""
                riptide:
                  exporters:
                    first:
                      address: 10.0.0.1
                """);
        this.reloader.poll();
        Files.delete(this.file);
        this.reloader.poll();

        write("""
                riptide:
                  exporters:
                    second:
                      address: 10.0.0.2
                """);
        this.reloader.poll();

        assertThat(this.inventory.snapshot().exporterView().match(netflow("10.0.0.2"))).isPresent();
        assertThat(successes()).isEqualTo(2);
        assertThat(failures()).isZero();
    }

    @Test
    void blankContentSkipsWithoutFailure() throws Exception {
        // whitespace-only intermediate states are truncate-race shapes like 0 bytes
        write("\n  \n\t\n");
        this.reloader.poll();

        assertThat(failures()).isZero();
        assertThat(successes()).isZero();
        assertThat(stale()).isZero();
    }

    @Test
    void malformedUtf8IsRejectedKeepingTheLastGood() throws Exception {
        write("""
                riptide:
                  exporters:
                    core:
                      address: 10.0.0.1
                """);
        this.reloader.poll();

        // a lone Latin-1 byte: boot's strict read would refuse this file, so the
        // reload must too instead of committing U+FFFD-substituted content
        final byte[] latin1 = "riptide: {}\n# café\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(this.file, latin1);
        this.reloader.poll();

        assertThat(this.inventory.snapshot().exporterView().match(netflow("10.0.0.1"))).isPresent();
        assertThat(failures()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
    }

    @Test
    void disabledWithoutAFileStartsAndStopsSafely() {
        final InventoryConfig noFile = new InventoryConfig();
        final ConfigReloadProperties properties = new ConfigReloadProperties();
        properties.setReloadInterval(Duration.ofHours(1));
        final InventoryFileReloader disabled = new InventoryFileReloader(
                properties, noFile, this.inventory, this.poller, new MetricRegistry(), Optional.empty());

        disabled.start();
        disabled.stop();
    }

    /**
     * #539: gauges register from start(), so a reloader disabled by a missing interval
     * or a missing file publishes NO stale/dead gauges. A constant 0 read as "the file
     * matches what is serving" for a file that is never read again.
     */
    @Test
    void aDisabledReloaderRegistersNoGauges() {
        final MetricRegistry fresh = new MetricRegistry();
        final InventoryConfig withFile = new InventoryConfig();
        withFile.setFile(this.file);
        final var noInterval = new InventoryFileReloader(
                new ConfigReloadProperties(), withFile, this.inventory, this.poller, fresh, Optional.empty());
        noInterval.start();

        final ConfigReloadProperties hourly = new ConfigReloadProperties();
        hourly.setReloadInterval(Duration.ofHours(1));
        final var noFile = new InventoryFileReloader(
                hourly, new InventoryConfig(), this.inventory, this.poller, fresh, Optional.empty());
        noFile.start();

        assertThat(fresh.getGauges()).doesNotContainKeys("inventory.reload.stale", "inventory.reload.dead");
        // the counters exist and truthfully read zero
        assertThat(fresh.counter("inventory.reload.successes").getCount()).isZero();
        noInterval.stop();
        noFile.stop();
    }

    /**
     * #539: registration is remove-then-register, so a restarted bean (devtools, cached
     * test contexts) re-binds instead of throwing — and the gauges read the NEW
     * instance. Dropwizard's get-or-create would keep the OLD bean's lambda reading
     * dead fields, which is exactly what the final assert refutes: the ORIGINAL
     * reloader's schedule is still alive, so dead=1 can only come from the restarted
     * instance's cancelled one. The same cancelled handle is the dead-schedule gauge's
     * contract: an Error out of poll() cancels the task the same way.
     */
    @Test
    void gaugesRebindToARestartedInstanceAndReportItsDeath() throws IOException {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ConfigReloadProperties properties = new ConfigReloadProperties();
        properties.setReloadInterval(Duration.ofHours(1));
        final var restarted = new InventoryFileReloader(
                properties, config, this.inventory, this.poller, this.metrics, Optional.empty());
        restarted.start();
        assertThat(dead()).as("a live schedule is not a corpse").isZero();

        restarted.stop();
        assertThat(dead()).as("a cancelled schedule is a visible corpse").isEqualTo(1);
    }

    /**
     * #539: a poll that begins interrupted is shutdown, not a reload failure — it must
     * not read, count, or latch anything. (The mid-read ClosedByInterruptException belt
     * in the catch is deliberately untested: a PRE-SET flag does not fault the read on
     * this JDK — the first version of this test assumed it did and was vacuous, the
     * removal of the whole quiet path survived it.)
     */
    @Test
    void aPollBeginningInterruptedConsumesAndCountsNothing() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(InventoryFileReloader.class);
        final var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        final var appender = capture(InventoryFileReloader.class);
        Thread.currentThread().interrupt();
        try {
            this.reloader.poll();
        } finally {
            // clear the flag or it poisons the next test on this thread
            Thread.interrupted();
            release(InventoryFileReloader.class, appender);
            logger.setLevel(previousLevel);
        }
        assertThat(successes()).as("an interrupted poll reads nothing").isZero();
        assertThat(failures()).as("shutdown is not a failure").isZero();
        assertThat(stale()).isZero();
        // the before-poll sentence, not the mid-cycle one: the two are adjacent String
        // arguments, both DEBUG, and only the mid-cycle one carries a placeholder — so a
        // swap would read plausibly while silently dropping an exception message
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Inventory reload poll skipped: thread interrupted (shutdown)"));

        // the content was never consumed, so the next clean poll serves it normally
        this.reloader.poll();
        assertThat(successes()).isEqualTo(1);
    }

    /**
     * #539: the loader's walk warnings describe live state ("it still matches, so it
     * can shadow wider ranges"), so a candidate that FAILS discards them unlogged — the
     * log used to read as though the warned-about state went live when nothing changed.
     */
    @Test
    void aRejectedCandidatesWarningsNeverReachTheLog() throws Exception {
        final var appender = capture(org.riptide.inventory.InventoryLoader.class);
        try {
            // an early entry worth a warning, a later entry that throws: the whole
            // candidate dies, and the warning must die with it
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.99.0.0/24": {}
                      exporters:
                        bad: {}
                    """);
            this.reloader.poll();

            assertThat(failures()).isEqualTo(1);
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains("declares nothing"));
        } finally {
            release(org.riptide.inventory.InventoryLoader.class, appender);
        }
    }

    /** The other half: a PUBLISHED candidate's warnings flush, exactly once. */
    @Test
    void aPublishedCandidatesWarningsFlushExactlyOnce() throws Exception {
        final var appender = capture(org.riptide.inventory.InventoryLoader.class);
        try {
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.99.0.0/24": {}
                    """);
            this.reloader.poll();

            assertThat(successes()).isEqualTo(1);
            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().contains(
                            "Agent range '10.99.0.0/24' declares nothing"))
                    .hasSize(1);
        } finally {
            release(org.riptide.inventory.InventoryLoader.class, appender);
        }
    }

    /**
     * And the refusal path (#535's guard): a torn one-tree candidate carrying a
     * warning-worthy entry logs the refusal, never the walk warning — the warned-about
     * pin does not exist in what is serving.
     */
    @Test
    void aRefusedTornCandidatesWarningsStayUnflushed() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        this.reloader.poll();
        assertThat(this.inventory.snapshot().agentCount()).isEqualTo(1);

        final var appender = capture(org.riptide.inventory.InventoryLoader.class);
        try {
            // agents tree gone (torn) + a pins-nothing interface worth a warning
            write("""
                    riptide:
                      exporters:
                        core:
                          address: 10.20.0.1
                          interfaces:
                            3: {}
                    """);
            this.reloader.poll();

            assertThat(this.inventory.snapshot().agentCount())
                    .as("refused: the fleet survives").isEqualTo(1);
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains("pins nothing"));
        } finally {
            release(org.riptide.inventory.InventoryLoader.class, appender);
        }
    }

    /**
     * The double fault (#559): the snapshot publishes and then the refresh throws. The
     * inventory IS serving, so this must not be counted or latched as a failed reload —
     * the guard here predates #555, is unpinned, and removing it sends the throw into
     * poll()'s catch, which would report a live snapshot as a failure.
     */
    @Test
    void aCommittedReloadSurvivesARefreshFailure() throws Exception {
        this.poller.throwOnRefresh = true;
        final var appender = capture(InventoryFileReloader.class);
        try {
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.44.0.0/16":
                            credentials: corp-v3
                      exporters:
                        core:
                          address: 10.44.0.1
                    """);
            this.reloader.poll();
        } finally {
            release(InventoryFileReloader.class, appender);
        }

        // the refresh was attempted, and the content it was meant to re-resolve IS serving
        assertThat(this.poller.refreshes).isEqualTo(1);
        assertThat(this.inventory.snapshot().agentView().match(netflow("10.44.5.5"))).isPresent();
        assertThat(successes()).isEqualTo(1);
        assertThat(failures()).as("a live snapshot is not a failed reload").isZero();
        assertThat(stale()).as("what is serving matches the file").isZero();
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("refreshing polled endpoints failed"));
    }

    /**
     * Loses the compare-and-swap once, exactly as a concurrent main-config reload does
     * when it republishes the profiles while this candidate is being parsed. That race
     * cannot be scheduled from a single-threaded test, so it is injected here instead.
     */
    private static final class RacingInventory extends Inventory {
        private int refusals;

        private RacingInventory(final SnmpProfilesConfig profiles, final InventoryConfig config) {
            super(profiles, new FileInventoryDocument(config));
        }

        @Override
        public synchronized boolean swapIfProfilesUnchanged(final SnmpProfilesConfig parsedWith,
                                                            final org.riptide.inventory.InventorySnapshot snapshot) {
            if (this.refusals > 0) {
                this.refusals--;
                return false;
            }
            return super.swapIfProfilesUnchanged(parsedWith, snapshot);
        }
    }

    /**
     * A deferred candidate is re-offered on the following cycle. The deferral resets the
     * attempted hash — {@code rollbackAttempt()}, not {@code markCommitted()}, two
     * adjacent no-arg calls on the same object five lines apart. With the wrong one the
     * candidate is recorded as committed, the next cycle short-circuits it as unchanged,
     * the stale gauge reads 0, and the edit is dropped permanently with nothing said.
     */
    @Test
    void aDeferredCandidateIsReOfferedOnTheNextCycle() throws Exception {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ConfigReloadProperties properties = new ConfigReloadProperties();
        properties.setReloadInterval(Duration.ofHours(1));
        final RacingInventory racing = new RacingInventory(this.profiles, config);
        racing.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var deferring = new InventoryFileReloader(properties, config, racing, this.poller, fresh,
                Optional.empty());
        deferring.start();
        try {
            racing.refusals = 1;
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            credentials: corp-v3
                    """);

            deferring.poll();
            assertThat(fresh.counter("inventory.reload.successes").getCount())
                    .as("deferred: nothing was published").isZero();
            assertThat(racing.snapshot().agentCount()).isZero();
            assertThat(fresh.counter("inventory.reload.failures").getCount())
                    .as("a lost race is not a failure").isZero();

            // the SAME unchanged bytes must be read and parsed again, not short-circuited
            deferring.poll();
            assertThat(fresh.counter("inventory.reload.successes").getCount()).isEqualTo(1);
            assertThat(racing.snapshot().agentView().match(netflow("10.20.5.5"))).isPresent();
            assertThat((Integer) ((Gauge<?>) fresh.getGauges().get("inventory.reload.stale")).getValue())
                    .isZero();
        } finally {
            deferring.stop();
        }
    }

    /**
     * The poll thread carries this reloader's name. {@code threadName} and
     * {@code metricPrefix} are adjacent String arguments to the trigger
     * ("InventoryFileReloader" / "inventory"); swapping them compiles, and the thread
     * name is the half nothing else reads.
     */
    @Test
    void thePollThreadIsNamedForThisReloader() {
        assertThat(Thread.getAllStackTraces().keySet())
                .anyMatch(thread -> "InventoryFileReloader".equals(thread.getName()));
    }

    /** Discovery on: the composed document over this test's file, with a fixed endpoint answer. */
    private static ComposedInventoryDocument composedOver(final InventoryConfig config) {
        final DiscoveryConfig discovery = new DiscoveryConfig();
        discovery.setUrl("https://netbox.example.com/api/devices/");
        discovery.setInterval(Duration.ofHours(1));
        return new ComposedInventoryDocument(new FileInventoryDocument(config),
                new ServiceDiscoverySource(() -> """
                        [{"targets":["firewall-01"],
                          "labels":{"__meta_netbox_name":"firewall-01",
                                    "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                        """.getBytes(StandardCharsets.UTF_8),
                        () -> "the endpoint"),
                () -> "the endpoint", discovery, new MetricRegistry());
    }

    /**
     * The requirement the composed source exists for. With discovery on the file carries no
     * exporters tree, so a watcher re-reading the file alone would offer a candidate with none,
     * the regression pre-check would refuse it every cycle, and this edit would never apply. The
     * config reload interval is left unset: discovery's own interval is what enables the watcher.
     */
    @Test
    void withDiscoveryOnAnAgentRangeEditInTheFileApplies() throws Exception {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed = composedOver(config);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            credentials: corp-v3
                    """);
            watcher.poll();

            assertThat(composedInventory.snapshot().agentView().match(netflow("10.20.5.5")))
                    .as("the file's agent-range edit applied")
                    .isPresent();
            assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1")))
                    .as("and the exporters still come from discovery")
                    .isPresent();
            assertThat(fresh.counter("inventory.reload.successes").getCount()).isEqualTo(1);
        } finally {
            watcher.stop();
        }
    }

    /**
     * A composed candidate that drops the file's agent ranges is refused exactly like a file one,
     * but the WARN names the composed document rather than a file, and offers only advice that can
     * be followed: an exporters tree in the file is refused outright while discovery is on.
     */
    @Test
    void withDiscoveryOnARefusalNamesTheComposedDocumentAndOnlyAdviceThatCanBeFollowed() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed = composedOver(config);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, new MetricRegistry(), Optional.of(composed));
        watcher.start();
        final var appender = capture(InventoryFileReloader.class);
        try {
            // a torn write: the writer reached the `agents:` key and stopped, so the tree is gone
            // and nothing declared it empty. NOT `riptide: {}`, which this test used to write and
            // call a torn write: that spelling is the documented broad decommission, truncation
            // cannot produce it, and reading it as a partial write was the defect in #805
            write("""
                    riptide:
                      snmp:
                        agents:
                    """);
            watcher.poll();
        } finally {
            release(InventoryFileReloader.class, appender);
            watcher.stop();
        }

        assertThat(composedInventory.snapshot().agentCount())
                .as("the polled fleet survives").isEqualTo(1);
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .startsWith("Inventory source " + this.file + " + the endpoint would drop a whole tree")
                .contains("1 -> 0 agent range(s)")
                // the name of this test promises advice that can be followed, and until #803 it
                // asserted only the subject: the message still prescribed an mv against a composed
                // document, where the half that was short may be the endpoint's response
                .doesNotContain("write atomically via mv")
                .doesNotContain("partially written file")
                // both spellings that reach the file's own tree, because the discovery page
                // promises both and an operator told only one would think the other had stopped
                // working. Not "exporters: {}", which the file may not write while discovery owns it
                .contains("agents: {}")
                .contains("riptide: {}")
                .doesNotContain("exporters: {}"));
    }

    /**
     * The twin of the test above, and the one #805 was about. A broad {@code riptide: {}} is the
     * documented way to decommission everything the file still owns, so with discovery on it must
     * publish rather than be refused: composition inserts an exporters tree into that map, and
     * before the fix that silently revoked the declaration and wedged every poll.
     */
    @Test
    void withDiscoveryOnABroadEmptyDeclarationDecommissionsTheFleet() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed = composedOver(config);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, new MetricRegistry(), Optional.of(composed));
        watcher.start();
        try {
            assertThat(composedInventory.snapshot().agentCount()).isEqualTo(1);
            write("riptide: {}");
            watcher.poll();
        } finally {
            watcher.stop();
        }

        assertThat(composedInventory.snapshot().agentCount())
                .as("the operator asked for the fleet to be empty and the file still owns that tree")
                .isZero();
        assertThat(composedInventory.snapshot().exporterCount())
                .as("discovery still owns the exporters tree, so it is unaffected")
                .isPositive();
    }

    /**
     * A boot that could not reach the endpoint served the file's trees alone, and this watcher is
     * what heals it. Two things must hold. The gauge reads 1 from the start, before any cycle: a 404
     * skips cycles without recomputing staleness, so a latch set only by a failed cycle would never
     * come. And the hashes are not seeded: the endpoint answers by the time the watcher starts here,
     * so a seed would record the discovered document as committed while the file-only one serves,
     * and the first cycle would skip it as unchanged forever.
     */
    @Test
    void withDiscoveryOnABootThatCouldNotReachTheEndpointIsStaleUntilTheWatcherPublishes() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final DiscoveryConfig discovery = new DiscoveryConfig();
        discovery.setUrl("https://netbox.example.com/api/devices/");
        discovery.setInterval(Duration.ofHours(1));
        final java.util.concurrent.atomic.AtomicBoolean up = new java.util.concurrent.atomic.AtomicBoolean();
        final ComposedInventoryDocument composed = new ComposedInventoryDocument(new FileInventoryDocument(config),
                new ServiceDiscoverySource(() -> {
                    if (!up.get()) {
                        throw new IOException("connection refused");
                    }
                    return """
                            [{"targets":["firewall-01"],
                              "labels":{"__meta_netbox_name":"firewall-01",
                                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                            """.getBytes(StandardCharsets.UTF_8);
                }, () -> "the endpoint"),
                () -> "the endpoint", discovery, new MetricRegistry());
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        assertThat(composedInventory.snapshot().agentCount()).as("boot served the file").isEqualTo(1);
        assertThat(composedInventory.snapshot().exporterCount()).as("with no exporters").isZero();

        up.set(true);
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            assertThat((Integer) ((Gauge<?>) fresh.getGauges().get("inventory.reload.stale")).getValue())
                    .as("stale from the start: what serves is not what the source says")
                    .isEqualTo(1);

            watcher.poll();

            assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1")))
                    .as("the first cycle that reaches the endpoint publishes its exporters")
                    .isPresent();
            assertThat(composedInventory.snapshot().agentCount()).isEqualTo(1);
            assertThat((Integer) ((Gauge<?>) fresh.getGauges().get("inventory.reload.stale")).getValue())
                    .as("and clears the gauge")
                    .isZero();
        } finally {
            watcher.stop();
        }
    }

    /**
     * A 404 after a healthy publish is what an operator gets when a NetBox plugin path changes
     * under a running collector: nothing fails, nothing is counted, and every later discovery
     * change silently stops arriving. The docs promise {@code inventory.reload.stale} says so, and
     * the gauge read 0 forever instead, because absence skips the cycle without recomputing it.
     *
     * <p>The failure counter is asserted throughout: a 404 is absence, not failure, and the
     * contract that a cycle which read nothing never moves {@code inventory.reload.failures} is
     * older than discovery (#539).</p>
     */
    @Test
    void withDiscoveryOnAnEndpointThatStarts404ingAfterAPublishRaisesStaleAndClearsOnRecovery()
            throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final DiscoveryConfig discovery = new DiscoveryConfig();
        discovery.setUrl("https://netbox.example.com/api/devices/");
        discovery.setInterval(Duration.ofHours(1));
        final java.util.concurrent.atomic.AtomicBoolean present =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final ComposedInventoryDocument composed = new ComposedInventoryDocument(
                new FileInventoryDocument(config),
                new ServiceDiscoverySource(() -> {
                    if (!present.get()) {
                        // exactly what DiscoveryClient raises for a 404, and the only ENDPOINT
                        // failure the composed document turns into absence (a missing inventory
                        // file is the other absence, and it does not latch this gauge)
                        throw new java.io.FileNotFoundException("the endpoint answered 404");
                    }
                    return """
                            [{"targets":["firewall-01"],
                              "labels":{"__meta_netbox_name":"firewall-01",
                                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                            """.getBytes(StandardCharsets.UTF_8);
                }, () -> "the endpoint"),
                () -> "the endpoint", discovery, new MetricRegistry());
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            // a healthy cycle first: the gauge has to have been 0 for a later 1 to mean anything
            write("""
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            credentials: corp-v3
                          "10.30.0.0/16":
                            credentials: corp-v3
                    """);
            watcher.poll();
            assertThat(staleOf(fresh)).as("a healthy poll published and the gauge is clean").isZero();
            assertThat(fresh.counter("inventory.reload.failures").getCount()).isZero();

            present.set(false);
            watcher.poll();

            assertThat(staleOf(fresh))
                    .as("the endpoint answers 404: what discovery would serve is unknown, so stale")
                    .isEqualTo(1);
            assertThat(fresh.counter("inventory.reload.failures").getCount())
                    .as("absence is not failure: the counter must not move")
                    .isZero();
            assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1")))
                    .as("and the last good inventory keeps serving")
                    .isPresent();

            present.set(true);
            watcher.poll();

            assertThat(staleOf(fresh))
                    .as("the endpoint recovered with the same document, so the gauge clears again")
                    .isZero();
            assertThat(fresh.counter("inventory.reload.failures").getCount()).isZero();
        } finally {
            watcher.stop();
        }
    }

    /**
     * With discovery off a missing inventory file is a skip: the trigger warns once and keeps
     * serving. With discovery on it was a counted failure, logged with a stack trace on every poll
     * forever, because the composed document mapped only {@code FileNotFoundException} to absence
     * while {@code FileInventoryDocument} wraps a {@code NoSuchFileException}. An operator reaches
     * it by deleting the file after boot, and through the {@code rm}+{@code mv} replacement this
     * project's own skip message recommends, which has a real window where the read sees the gap.
     */
    @Test
    void withDiscoveryOnADeletedInventoryFileIsAbsenceAndIsNeverCounted() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed = composedOver(config);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            Files.delete(this.file);

            watcher.poll();
            watcher.poll();

            assertThat(fresh.counter("inventory.reload.failures").getCount())
                    .as("a missing inventory file is absence, exactly as it is with discovery off")
                    .isZero();
            assertThat(composedInventory.snapshot().agentCount())
                    .as("and the last good inventory keeps serving")
                    .isEqualTo(1);
            assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1")))
                    .as("exporters included")
                    .isPresent();
        } finally {
            watcher.stop();
        }
    }

    /**
     * The other half of the same distinction: a path that is <em>there</em> and cannot be read is a
     * real failure and has to keep being counted. Produced with a directory, which stats fine and
     * fails the read — the portable stand-in for the permission denial. Removing read permission is
     * not used, because it does not deny a process running as root, which is how this suite runs in
     * a container; a chmod-based test would pass there by skipping the guard entirely. The
     * permission denial's own exception type is pinned in {@code ComposedInventoryDocumentTest}.
     *
     * <p>A path under a regular file was tried first and is not usable either: macOS answers
     * {@code Files.size} for it with {@code NoSuchFileException}, so it reads as absence.</p>
     */
    @Test
    void withDiscoveryOnAnUnreadableInventoryPathIsStillACountedFailure() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed = composedOver(config);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            // the document re-reads InventoryConfig.getFile() on every fetch, so this is what a
            // file turning unreadable under a running collector looks like
            config.setFile(Files.createDirectory(this.tempDir.resolve("unreadable")));

            watcher.poll();

            assertThat(fresh.counter("inventory.reload.failures").getCount())
                    .as("there and unreadable is a failure, not absence: an operator told to make a "
                            + "file reappear that is already there has been sent to the wrong place")
                    .isEqualTo(1);
        } finally {
            watcher.stop();
        }
    }

    private static final String DEVICES_URL = "https://netbox.example.com/api/dcim/devices/";
    private static final String VMS_URL = "https://netbox.example.com/api/virtualization/virtual-machines/";

    private static byte[] oneExporter(final String name, final String address) {
        return """
                [{"targets":["%s"],
                  "labels":{"__meta_netbox_name":"%s",
                            "__meta_netbox_primary_ip4":"%s"}}]
                """.formatted(name, name, address).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Discovery over two endpoints, devices then virtual machines, the shape
     * {@code riptide.discovery.urls} builds. Each answers whatever its reference holds: bytes, or an
     * exception to throw.
     */
    private static ComposedInventoryDocument twoEndpointsOver(final InventoryConfig config,
                                                              final AtomicReference<Object> vms) {
        final DiscoveryConfig discovery = new DiscoveryConfig();
        discovery.setUrls(List.of(DEVICES_URL, VMS_URL));
        discovery.setInterval(Duration.ofHours(1));
        final ServiceDiscoverySource.Fetcher vmFetcher = () -> {
            if (vms.get() instanceof IOException failure) {
                throw failure;
            }
            return (byte[]) vms.get();
        };
        return new ComposedInventoryDocument(new FileInventoryDocument(config),
                new DiscoveryEndpoints(List.of(
                        new DiscoveryEndpoints.Endpoint(() -> DEVICES_URL,
                                new ServiceDiscoverySource(() -> oneExporter("firewall-01", "10.0.0.1"),
                                        () -> DEVICES_URL)),
                        new DiscoveryEndpoints.Endpoint(() -> VMS_URL,
                                new ServiceDiscoverySource(vmFetcher, () -> VMS_URL)))),
                discovery, new MetricRegistry());
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureRiptide() {
        final var appender = LogCapture.startedAppender();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.riptide")).addAppender(appender);
        return appender;
    }

    private static void releaseRiptide(
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.riptide")).detachAppender(appender);
    }

    @Test
    void withTwoEndpointsBootNamesBothAndServesTheExportersOfEach() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final ComposedInventoryDocument composed =
                twoEndpointsOver(config, new AtomicReference<>(oneExporter("hook", "10.0.0.2")));
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        final var appender = capture(Inventory.class);
        try {
            composedInventory.load();
        } finally {
            release(Inventory.class, appender);
        }

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Inventory loaded from %s + %s, %s: 1 agent ranges, 2 enrichment entries"
                        .formatted(this.file, DEVICES_URL, VMS_URL)));
        assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1"))).isPresent();
        assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.2"))).isPresent();
    }

    @Test
    void withTwoEndpointsOneFailingAfterBootIsOneCountedFailureNamingItAndTheLastGoodServes() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final AtomicReference<Object> vms = new AtomicReference<>(oneExporter("hook", "10.0.0.2"));
        final ComposedInventoryDocument composed = twoEndpointsOver(config, vms);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        final var appender = captureRiptide();
        try {
            vms.set(new IOException(VMS_URL + " answered HTTP 500, not 200"));
            watcher.poll();
        } finally {
            releaseRiptide(appender);
            watcher.stop();
        }

        assertThat(fresh.counter("inventory.reload.failures").getCount()).isEqualTo(1);
        assertThat(appender.list).anySatisfy(event -> assertThat(String.valueOf(event.getThrowableProxy() == null
                ? event.getFormattedMessage()
                : event.getFormattedMessage() + " " + event.getThrowableProxy().getMessage()))
                .contains(VMS_URL + " could not be read: " + VMS_URL + " answered HTTP 500"));
        assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.1")))
                .as("the device endpoint answered, and still nothing of this poll was published")
                .isPresent();
        assertThat(composedInventory.snapshot().exporterView().match(netflow("10.0.0.2")))
                .as("the last good inventory keeps serving the virtual machine")
                .isPresent();
    }

    @Test
    void withTwoEndpointsOneAnswering404IsAbsenceStaleAndNamedOnce() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final AtomicReference<Object> vms = new AtomicReference<>(oneExporter("hook", "10.0.0.2"));
        final ComposedInventoryDocument composed = twoEndpointsOver(config, vms);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        composedInventory.load();
        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        final var appender = capture(ComposedInventoryDocument.class);
        try {
            vms.set(new java.io.FileNotFoundException(VMS_URL + " answered 404"));
            watcher.poll();
            watcher.poll();
        } finally {
            release(ComposedInventoryDocument.class, appender);
            watcher.stop();
        }

        assertThat(staleOf(fresh)).isEqualTo(1);
        assertThat(fresh.counter("inventory.reload.failures").getCount()).as("absence is not failure").isZero();
        assertThat(appender.list)
                .as("which endpoint is absent is said once an episode, not every poll")
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .isEqualTo(VMS_URL + " could not be read: " + VMS_URL + " answered 404"));
    }

    /**
     * A second endpoint going absent before the first recovers is a different fault, and naming
     * only the first would send the operator back to an endpoint they already fixed.
     */
    @Test
    void withTwoEndpointsASecondEndpointGoingAbsentInTheSameEpisodeIsNamedToo() throws Exception {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final DiscoveryConfig discovery = new DiscoveryConfig();
        discovery.setUrls(List.of(DEVICES_URL, VMS_URL));
        final AtomicReference<Object> devices = new AtomicReference<>(
                new java.io.FileNotFoundException(DEVICES_URL + " answered 404"));
        final AtomicReference<Object> vms = new AtomicReference<>(oneExporter("hook", "10.0.0.2"));
        final ComposedInventoryDocument composed = new ComposedInventoryDocument(new FileInventoryDocument(config),
                new DiscoveryEndpoints(List.of(
                        new DiscoveryEndpoints.Endpoint(() -> DEVICES_URL, new ServiceDiscoverySource(() -> {
                            if (devices.get() instanceof IOException failure) {
                                throw failure;
                            }
                            return (byte[]) devices.get();
                        }, () -> DEVICES_URL)),
                        new DiscoveryEndpoints.Endpoint(() -> VMS_URL, new ServiceDiscoverySource(() -> {
                            if (vms.get() instanceof IOException failure) {
                                throw failure;
                            }
                            return (byte[]) vms.get();
                        }, () -> VMS_URL)))),
                discovery, new MetricRegistry());
        final var appender = capture(ComposedInventoryDocument.class);
        try {
            composed.fetch();
            devices.set(oneExporter("firewall-01", "10.0.0.1"));
            vms.set(new java.io.FileNotFoundException(VMS_URL + " answered 404"));
            composed.fetch();
            composed.fetch();
        } finally {
            release(ComposedInventoryDocument.class, appender);
        }

        assertThat(appender.list).extracting(event -> event.getFormattedMessage())
                .containsExactly(DEVICES_URL + " could not be read: " + DEVICES_URL + " answered 404",
                        VMS_URL + " could not be read: " + VMS_URL + " answered 404");
    }

    @Test
    void withTwoEndpointsOneDownAtBootStartsWithNoDiscoveredExportersUntilBothAnswer() throws Exception {
        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);
        final InventoryConfig config = new InventoryConfig();
        config.setFile(this.file);
        final AtomicReference<Object> vms = new AtomicReference<>(new IOException("Connection refused"));
        final ComposedInventoryDocument composed = twoEndpointsOver(config, vms);
        final Inventory composedInventory = new Inventory(this.profiles, composed);
        final var appender = capture(ComposedInventoryDocument.class);
        try {
            composedInventory.load();
        } finally {
            release(ComposedInventoryDocument.class, appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .startsWith("Boot could not reach " + VMS_URL + ": Connection refused."));
        assertThat(composedInventory.snapshot().exporterCount())
                .as("the device endpoint answered, and none of its exporters are served either")
                .isZero();

        final MetricRegistry fresh = new MetricRegistry();
        final var watcher = new InventoryFileReloader(new ConfigReloadProperties(), config,
                composedInventory, this.poller, fresh, Optional.of(composed));
        watcher.start();
        try {
            assertThat(staleOf(fresh)).isEqualTo(1);
            watcher.poll();
            assertThat(staleOf(fresh)).as("still one endpoint down").isEqualTo(1);

            vms.set(oneExporter("hook", "10.0.0.2"));
            watcher.poll();
            assertThat(staleOf(fresh)).as("both answer").isZero();
            assertThat(composedInventory.snapshot().exporterCount()).isEqualTo(2);
        } finally {
            watcher.stop();
        }
    }

    private static int staleOf(final MetricRegistry registry) {
        return (Integer) ((Gauge<?>) registry.getGauges().get("inventory.reload.stale")).getValue();
    }

    private int dead() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("inventory.reload.dead")).getValue();
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture(
            final Class<?> loggerClass) {
        final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        return appender;
    }

    private static void release(final Class<?> loggerClass,
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass)).detachAppender(appender);
    }

    private void write(final String yaml) throws IOException {
        Files.writeString(this.file, yaml);
    }

    private long successes() {
        return this.metrics.counter("inventory.reload.successes").getCount();
    }

    private long failures() {
        return this.metrics.counter("inventory.reload.failures").getCount();
    }

    private int stale() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("inventory.reload.stale")).getValue();
    }

    private static ExporterIdentity netflow(final String address) {
        try {
            return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
