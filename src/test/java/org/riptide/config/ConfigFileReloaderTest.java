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
import org.riptide.node.NodeRegistry;
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
 * additional-location precedence. Polls are driven manually — the scheduled interval
 * is far in the future.
 */
@SpringBootTest
public class ConfigFileReloaderTest {

    private static final Path CONFIG = createTempConfigPath();

    @Autowired
    private ConfigFileReloader reloader;

    @Autowired
    private NodeRegistry nodeRegistry;

    @Autowired
    private ExporterInterfaceTable exporterInterfaceTable;

    @Autowired
    private MetricRegistry metrics;

    private static Path createTempConfigPath() {
        try {
            return Files.createTempDirectory("riptide-reload-test").resolve("config.yaml");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reloadProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.config.additional-location", () -> "optional:file:" + CONFIG);
        registry.add("riptide.config.reload-interval", () -> "1h");
        // stands in for the environment-variable layer: test properties outrank files
        registry.add("riptide.nodes.envnode.subnet-address", () -> "192.0.2.0/24");
        registry.add("riptide.nodes.envnode.observation-domain", () -> "99");
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    private void write(final String yaml) throws IOException {
        Files.writeString(CONFIG, yaml);
    }

    private ExporterIdentity identity(final String host, final long domain) throws Exception {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    @Test
    public void fileCreatedAfterBootAppliesBeneathOverridesAndBindsFully() throws Exception {
        write("""
                riptide:
                  nodes:
                    envnode:
                      subnet-address: 192.0.2.0/24
                      observation-domain: 1
                    filenode:
                      subnet-address: 10.7.0.0/16
                      snmp:
                        snmp-version: v2c
                        community: env://RELOAD_TEST_COMMUNITY
                      interfaces:
                        "3":
                          name: reload-if3
                  routing:
                    prefixes:
                      "[203.0.113.0/24]": { asn: 64500, org: "Reloaded Org" }
                """);
        reloader.poll();

        // file node fully bound: subnet, SecretRef ctor-binding, interfaces map
        final var fileNode = nodeRegistry.lookup(identity("10.7.1.1", 0)).orElseThrow();
        assertThat(fileNode.label()).isEqualTo("filenode");
        assertThat(fileNode.definition().getSnmp().getCommunity().getScheme()).isEqualTo("env");
        assertThat(fileNode.definition().getInterfaces().get(3).name()).isEqualTo("reload-if3");

        // the override layer still wins: envnode's pinned domain stays 99, not the file's 1
        assertThat(nodeRegistry.lookup(identity("192.0.2.5", 99))).map(n -> n.label()).contains("envnode");
        assertThat(nodeRegistry.lookup(identity("192.0.2.5", 1))).isEmpty();

        assertThat(metrics.counter("config.reload.successes").getCount()).isPositive();
    }

    @Test
    public void invalidCandidateKeepsServingTheOldConfig() throws Exception {
        write("""
                riptide:
                  nodes:
                    good:
                      subnet-address: 10.8.0.0/16
                """);
        reloader.poll();
        assertThat(nodeRegistry.lookup(identity("10.8.1.1", 0))).isPresent();
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();

        write("""
                riptide:
                  nodes:
                    dup-a:
                      subnet-address: 10.9.0.0/16
                    dup-b:
                      subnet-address: 10.9.0.0/16
                """);
        reloader.poll();

        // old config keeps serving; failure counted; staleness visible
        assertThat(nodeRegistry.lookup(identity("10.8.1.1", 0))).isPresent();
        assertThat(nodeRegistry.lookup(identity("10.9.1.1", 0))).isEmpty();
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
    public void legacyIndexedKeysRejectTheCandidate() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  nodes:
                    - subnet-address: 10.10.0.0/16
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
                  nodes:
                    another:
                      subnet-address: 10.11.0.0/16
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
                  nodes:
                    steady:
                      subnet-address: 10.12.0.0/16
                """);
        reloader.poll();
        final long successesAfterFirst = metrics.counter("config.reload.successes").getCount();

        reloader.poll();

        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesAfterFirst);
    }

    @Test
    public void laterYamlDocumentsOverrideEarlierOnesLikeBoot() throws Exception {
        write("""
                riptide:
                  nodes:
                    multidoc:
                      subnet-address: 10.13.0.0/16
                      observation-domain: 1
                ---
                riptide:
                  nodes:
                    multidoc:
                      subnet-address: 10.13.0.0/16
                      observation-domain: 2
                """);
        reloader.poll();

        // boot semantics: the LAST document wins
        assertThat(nodeRegistry.lookup(identity("10.13.1.1", 2))).isPresent();
        assertThat(nodeRegistry.lookup(identity("10.13.1.1", 1))).isEmpty();
    }

    @Test
    public void profileGatedDocumentsAreSkippedNotApplied() throws Exception {
        write("""
                riptide:
                  nodes:
                    plain:
                      subnet-address: 10.14.0.0/16
                ---
                spring:
                  config:
                    activate:
                      on-profile: never-active
                riptide:
                  nodes:
                    gated:
                      subnet-address: 10.15.0.0/16
                """);
        reloader.poll();

        assertThat(nodeRegistry.lookup(identity("10.14.1.1", 0))).isPresent();
        assertThat(nodeRegistry.lookup(identity("10.15.1.1", 0))).isEmpty();
    }

    @Test
    public void fileInsertsAboveClasspathDefaults(@Autowired final org.springframework.core.env.ConfigurableEnvironment environment) throws Exception {
        // classpath application.properties sets riptide.snmp.cache.retentionMs=600000;
        // the reloaded file must outrank it (additional-location precedence)
        write("""
                riptide:
                  snmp:
                    cache:
                      retentionMs: 123
                  nodes:
                    precedence:
                      subnet-address: 10.16.0.0/16
                """);
        reloader.poll();

        assertThat(environment.getProperty("riptide.snmp.cache.retentionMs")).isEqualTo("123");
    }
}
