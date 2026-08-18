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
import org.riptide.inventory.TestCredentials;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.riptide.snmp.SnmpPollConfig;
import org.riptide.pipeline.ExporterIdentity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
    }
    /** Counts refreshes so the reload trigger is observable; the sweep itself is a no-op here. */
    private static final class CountingPoller extends InterfaceSnapshotPoller {
        private int refreshes;

        private CountingPoller(final Inventory inventory, final MetricRegistry metrics) {
            super(new NoSnmp(), new SnmpPollConfig(), metrics, inventory);
        }

        @Override
        public void refreshRegistrations() {
            this.refreshes++;
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
        this.inventory = new Inventory(this.profiles, inventoryConfig);
        this.inventory.load();
        this.metrics = new MetricRegistry();
        // a poller with no scheduler and nothing registered: these tests exercise the
        // reload trigger, and the refresh half has its own tests in the poller suite
        this.poller = new CountingPoller(this.inventory, this.metrics);
        this.reloader = new InventoryFileReloader(properties, inventoryConfig, this.inventory,
                this.poller, this.metrics);
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

        write("""
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: nope
                """);
        this.reloader.poll();

        // swap rejected: the previous snapshot serves, failure counted, staleness latched
        assertThat(this.inventory.snapshot().agentView().match(netflow("10.20.5.5"))).isPresent();
        assertThat(failures()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
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
        final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
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
                properties, noFile, this.inventory, this.poller, new MetricRegistry());

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
                new ConfigReloadProperties(), withFile, this.inventory, this.poller, fresh);
        noInterval.start();

        final ConfigReloadProperties hourly = new ConfigReloadProperties();
        hourly.setReloadInterval(Duration.ofHours(1));
        final var noFile = new InventoryFileReloader(
                hourly, new InventoryConfig(), this.inventory, this.poller, fresh);
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
                properties, config, this.inventory, this.poller, this.metrics);
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
        Thread.currentThread().interrupt();
        try {
            this.reloader.poll();
        } finally {
            // clear the flag or it poisons the next test on this thread
            Thread.interrupted();
        }
        assertThat(successes()).as("an interrupted poll reads nothing").isZero();
        assertThat(failures()).as("shutdown is not a failure").isZero();
        assertThat(stale()).isZero();

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

    private int dead() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("inventory.reload.dead")).getValue();
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture(
            final Class<?> loggerClass) {
        final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
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
