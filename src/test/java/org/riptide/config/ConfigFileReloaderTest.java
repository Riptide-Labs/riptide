/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.snmp.IfInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration: the reloader's whole contract against a real Spring context. The
 * config file does not exist at boot (test property sources are applied after
 * ConfigData), so every test also exercises the file-created-after-boot insertion at
 * imported-file precedence. Polls are driven manually — the scheduled interval
 * is far in the future.
 */
@SpringBootTest
public class ConfigFileReloaderTest {

    private static final Path CONFIG = createTempConfigPath();

    /**
     * Declared at boot, like a real deployment: the inventory watcher captures its path at
     * start, so the config reloader deliberately will not follow a path that only appears
     * in a reloaded file.
     */
    private static final Path INVENTORY = createTempInventoryPath();

    private static Path createTempInventoryPath() {
        try {
            final Path file = Files.createTempFile("riptide-config-reload-inventory", ".yaml");
            file.toFile().deleteOnExit();
            // empty at boot: the config file is not part of the boot environment (only the
            // reloader reads it), so a credential reference here could not resolve yet
            Files.writeString(file, "riptide: {}\n");
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private ConfigFileReloader reloader;


    @Autowired
    private ExporterInterfaceTable exporterInterfaceTable;

    @Autowired
    private MetricRegistry metrics;

    @Autowired
    private org.riptide.inventory.Inventory inventory;

    private static Path createTempConfigPath() {
        try {
            final Path file = Files.createTempDirectory("riptide-reload-test").resolve("config.yaml");
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reloadProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "optional:file:" + CONFIG);
        registry.add("riptide.inventory.file", () -> INVENTORY.toString());
        registry.add("riptide.config.reload-interval", () -> "1h");
        // stands in for the environment-variable layer: test properties outrank files
        registry.add("riptide.snmp.credentials.envcred.version", () -> "v3");
        registry.add("riptide.snmp.credentials.envcred.security-name", () -> "from-the-override");
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        Files.deleteIfExists(CONFIG);
        // the inventory file is shared and one test rewrites it to reference a credential set
        // its own config defines. Left in place, every later test whose config omits that set
        // makes the inventory rebuild throw, which the reloader swallows into a warning: the
        // profiles then stay stale and any assertion on them fails, depending only on method
        // order. Reset to something non-empty (an empty inventory over a populated one is
        // refused) that references nothing
        Files.writeString(INVENTORY, """
                riptide:
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
    }

    @Test
    void malformedCredentialEditIsRejectedOnReloadNotAtTheNextBoot() throws Exception {
        // 2.3's bind-time shape validation must gate reload candidates too: a
        // half-configured USM pair committing cleanly here would fail the NEXT boot
        final long failuresBefore = this.metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      half:
                        version: v3
                        security-name: riptide
                        auth-passphrase: env://X
                """);
        this.reloader.poll();

        assertThat(this.metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    private void write(final String yaml) throws IOException {
        Files.writeString(CONFIG, yaml);
    }

    private ExporterIdentity identity(final String host, final long domain) throws Exception {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    @Test
    public void fileCreatedAfterBootAppliesBeneathOverridesAndBindsFully(
            @Autowired final org.springframework.core.env.ConfigurableEnvironment environment) throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      envcred:
                        version: v3
                        security-name: from-the-file
                      filecred:
                        version: v2c
                        community: env://RELOAD_TEST_COMMUNITY
                  routing:
                    prefixes:
                      "[203.0.113.0/24]": { asn: 64500, org: "Reloaded Org" }
                """);
        reloader.poll();

        // bound, not just present: the SecretRef constructor binding is the part most likely
        // to break silently, and asserting the raw property string would not exercise it
        final var credentials = inventory.profiles().credentials();
        assertThat(credentials.get("filecred").community().getScheme()).isEqualTo("env");
        assertThat(environment.getProperty("riptide.routing.prefixes[203.0.113.0/24].org"))
                .isEqualTo("Reloaded Org");

        // the override layer still wins: the property source outranks the file it sits above
        assertThat(credentials.get("envcred").securityName()).isEqualTo("from-the-override");

        assertThat(metrics.counter("config.reload.successes").getCount()).isPositive();
    }

    @Test
    public void invalidCandidateKeepsServingTheOldConfig() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      good:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("good");
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();

        write("""
                riptide:
                  snmp:
                    credentials:
                      broken:
                        version: v3
                """);
        reloader.poll();

        // old config keeps serving; failure counted; staleness visible
        assertThat(inventory.profiles().credentials()).containsKey("good").doesNotContainKey("broken");
        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isEqualTo(1);
    }

    @Test
    public void invalidRoutingAlsoRejectsTheCandidate() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  routing:
                    prefixes:
                      "[10.0.0.0/24]": { asn: 1 }
                      "[10.0.0.5/24]": { asn: 2 }
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void anyLegacyNodesTreeRejectsTheCandidate() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      still-serving:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("still-serving");

        // both spellings: the indexed list this check originally guarded, and the name-keyed
        // map that 0.9 removed outright. A running collector must not accept a reload that
        // adds either, now that nothing reads them
        for (final String legacy : new String[] {
                "riptide:\n  nodes:\n    - subnet-address: 10.10.0.0/16\n",
                "riptide:\n  nodes:\n    core:\n      subnet-address: 10.10.0.0/16\n"}) {
            final long failuresBefore = metrics.counter("config.reload.failures").getCount();
            write(legacy);
            reloader.poll();
            assertThat(metrics.counter("config.reload.failures").getCount())
                    .as("rejected: %s", legacy)
                    .isEqualTo(failuresBefore + 1);
            // rejected AND still serving: a check that took the collector down with it would
            // satisfy the counter and fail the requirement
            assertThat(inventory.profiles().credentials()).containsKey("still-serving");
        }
    }

    @Test
    public void rotatingACredentialReachesTheServingInventory() throws Exception {
        // the regression this closes: riptide.nodes used to carry credentials and
        // propagated them on reload. It is inert now, so riptide.snmp.credentials is the
        // only credential surface, and it used to be bound once at boot and never again:
        // rotating a compromised community would have needed a restart, silently
        // the range arrives with the credential that defines it, both through the reload
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.77.0.1":
                        credentials: corp
                """);
        write("""
                riptide:
                  snmp:
                    credentials:
                      corp:
                        version: v2c
                        community: old-community
                """);
        reloader.poll();

        assertThat(resolvedCommunity()).isEqualTo("old-community");

        write("""
                riptide:
                  snmp:
                    credentials:
                      corp:
                        version: v2c
                        community: new-community
                """);
        reloader.poll();

        // no restart: the profiles and the inventory built from them move together
        assertThat(resolvedCommunity()).isEqualTo("new-community");
    }

    private String resolvedCommunity() throws Exception {
        final var identity = new org.riptide.pipeline.ExporterIdentity.NetflowIpfix(
                java.net.InetAddress.getByName("10.77.0.1"), 0L);
        return this.inventory.snapshot().agentView().match(identity).orElseThrow()
                .credentials().community().getValue();
    }

    @Test
    public void anInventoryTreeInTheMainConfigRejectsTheCandidate() throws Exception {
        // boot refuses it, so a reload must too: accepting it would silently ignore the
        // tree until the next restart failed
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  exporters:
                    core-router:
                      address: 10.0.0.1
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void retiredPollKeysRejectTheCandidate() throws Exception {
        // a reload-accepted retired key would otherwise kill the NEXT boot
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 60000
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void exporterOptionDataSurvivesReload() throws Exception {
        final var exporter = identity("172.16.0.1", 0);
        exporterInterfaceTable.accept(exporter,
                List.of(new UnsignedValue("SCOPE:INTERFACE", 7)),
                List.of(new StringValue("IF_NAME", "persistent")));

        write("""
                riptide:
                  snmp:
                    credentials:
                      another:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        assertThat(exporterInterfaceTable.lookup(exporter, 7)).contains(new IfInfo("persistent", null, null));
    }

    @Test
    public void missingAndEmptyFilesSkipWithoutFailing() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        final long successesBefore = metrics.counter("config.reload.successes").getCount();

        reloader.poll(); // file deleted by cleanSlate

        write("");
        reloader.poll(); // truncate-write race shape

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore);
        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore);
    }

    @Test
    public void unchangedContentDoesNotRecommit() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      steady:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        // the reload must actually have committed, or "does not recommit" is trivially true
        assertThat(inventory.profiles().credentials()).containsKey("steady");
        final long successesAfterFirst = metrics.counter("config.reload.successes").getCount();

        reloader.poll();

        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesAfterFirst);
    }

    @Test
    public void laterYamlDocumentsOverrideEarlierOnesLikeBoot() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      multidoc:
                        version: v3
                        security-name: first
                ---
                riptide:
                  snmp:
                    credentials:
                      multidoc:
                        version: v3
                        security-name: second
                """);
        reloader.poll();

        // boot semantics: the LAST document wins
        assertThat(inventory.profiles().credentials().get("multidoc").securityName())
                .isEqualTo("second");
    }

    @Test
    public void profileGatedDocumentsAreSkippedNotApplied() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      plain:
                        version: v3
                        security-name: monitoring
                ---
                spring:
                  config:
                    activate:
                      on-profile: never-active
                riptide:
                  snmp:
                    credentials:
                      gated:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        assertThat(inventory.profiles().credentials())
                .containsKey("plain").doesNotContainKey("gated");
    }

    @Test
    public void retiredKeysInsideSkippedGatedDocumentsDoNotFailTheReload() throws Exception {
        // gated documents are never installed on reload, so a retired key in one
        // must not reject the candidate (it does not fail boot either — inactive
        // gated documents are never loaded there)
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      plain:
                        version: v3
                        security-name: monitoring
                ---
                spring:
                  config:
                    activate:
                      on-profile: never-active
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 60000
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore);
        assertThat(inventory.profiles().credentials()).containsKey("plain");
    }

    @Test
    public void fileInsertsAboveClasspathDefaults(@Autowired final org.springframework.core.env.ConfigurableEnvironment environment) throws Exception {
        // classpath application.properties sets riptide.snmp.cache.retentionMs=600000;
        // the reloaded file must outrank it (imported-file precedence)
        write("""
                riptide:
                  snmp:
                    cache:
                      retentionMs: 123
                    credentials:
                      precedence:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        assertThat(environment.getProperty("riptide.snmp.cache.retentionMs")).isEqualTo("123");
    }
}
