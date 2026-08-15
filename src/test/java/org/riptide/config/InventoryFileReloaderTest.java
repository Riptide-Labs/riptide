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
        this.reloader = new InventoryFileReloader(properties, inventoryConfig, this.profiles, this.inventory, this.metrics);
        this.reloader.start();
    }

    @AfterEach
    void tearDown() {
        this.reloader.stop();
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
                properties, noFile, this.profiles, this.inventory, new MetricRegistry());

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
