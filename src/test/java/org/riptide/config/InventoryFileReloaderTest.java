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
