/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.secrets.SecretRefConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryWiringTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfiguration.class);

    @Configuration
    @EnableConfigurationProperties({SnmpProfilesConfig.class, InventoryConfig.class})
    @Import({Inventory.class, InventoryMisplacementCheck.class, SecretRefConverter.class})
    static class WiringConfiguration {
    }

    @Test
    void startsWithoutAnInventoryFileServingTheEmptyInventory() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Inventory.class);
            assertThat(context.getBean(Inventory.class).snapshot()
                    .agentView().match(netflow("10.0.0.1", 0))).isEmpty();
        });
    }

    @Test
    void bootsWithAValidFileAndPublishesTheSnapshot() throws Exception {
        final Path file = this.tempDir.resolve("inventory.yaml");
        Files.writeString(file, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: corp-v3
                """);

        this.runner
                .withPropertyValues(
                        "riptide.inventory.file=" + file,
                        "riptide.snmp.credentials.corp-v3.version=v3",
                        "riptide.snmp.credentials.corp-v3.security-name=riptide",
                        "riptide.snmp.credentials.corp-v3.auth-protocol=sha1",
                        "riptide.snmp.credentials.corp-v3.auth-passphrase=env://SNMP_AUTH")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var agent = context.getBean(Inventory.class).snapshot()
                            .agentView().match(netflow("10.20.5.5", 0));
                    assertThat(agent).isPresent();
                    assertThat(agent.get().credentials().getSecurityName()).isEqualTo("riptide");
                    // the stated reason the profiles stay Spring-bound: SecretRef
                    // values bind through the existing converter
                    assertThat(agent.get().credentials().getAuthPassphrase()).isNotNull();
                });
    }

    @Test
    void malformedCredentialShapesFailBindNamingTheSetAndField() {
        this.runner
                .withPropertyValues("riptide.snmp.credentials.legacy.version=v2c")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("legacy")
                            .hasMessageContaining("community");
                });

        this.runner
                .withPropertyValues("riptide.snmp.credentials.versionless.security-name=riptide")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("versionless")
                            .hasMessageContaining("version");
                });
    }

    @Test
    void usmPairingIsValidatedAtBind() {
        // half-configured auth would be silently dropped at target build, walking
        // noAuthNoPriv while the operator believes it is authenticated
        this.runner
                .withPropertyValues(
                        "riptide.snmp.credentials.half.version=v3",
                        "riptide.snmp.credentials.half.security-name=riptide",
                        "riptide.snmp.credentials.half.auth-passphrase=env://X")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("half").hasMessageContaining("auth-protocol");
                });

        this.runner
                .withPropertyValues(
                        "riptide.snmp.credentials.privonly.version=v3",
                        "riptide.snmp.credentials.privonly.security-name=riptide",
                        "riptide.snmp.credentials.privonly.priv-protocol=aes128",
                        "riptide.snmp.credentials.privonly.priv-passphrase=env://X")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("privonly").hasMessageContaining("priv without auth");
                });
    }

    @Test
    void remainingShapeFailuresAreValidatedAtBind() {
        this.runner
                .withPropertyValues("riptide.snmp.credentials.old.version=v1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("old").hasMessageContaining("community");
                });

        this.runner
                .withPropertyValues("riptide.snmp.credentials.anonymous.version=v3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("anonymous").hasMessageContaining("security-name");
                });

        // an unknown version string fails in the binder naming the property path
        this.runner
                .withPropertyValues("riptide.snmp.credentials.typo.version=v9")
                .run(context -> {
                    assertThat(context).hasFailed();
                    final StringBuilder chain = new StringBuilder();
                    for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                        chain.append(cause.getMessage()).append('\n');
                    }
                    assertThat(chain).contains("riptide.snmp.credentials.typo.version");
                });
    }

    @Test
    void foreignFieldsAreRejectedNotSilentlyIgnored() {
        // leftover USM fields on a community set would read as authenticated while
        // every walk goes out plaintext; the reverse carries a dead community
        this.runner
                .withPropertyValues(
                        "riptide.snmp.credentials.migrated.version=v2c",
                        "riptide.snmp.credentials.migrated.community=public",
                        "riptide.snmp.credentials.migrated.security-name=riptide")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("migrated").hasMessageContaining("v3 fields");
                });

        this.runner
                .withPropertyValues(
                        "riptide.snmp.credentials.mixed.version=v3",
                        "riptide.snmp.credentials.mixed.security-name=riptide",
                        "riptide.snmp.credentials.mixed.community=public")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("mixed").hasMessageContaining("community");
                });
    }

    @Test
    void inventoryTreesInMainConfigFailStartupNamingTheKey() {
        this.runner
                .withPropertyValues("riptide.snmp.agents.10.0.0.0/24.credentials=corp-v3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("riptide.snmp.agents")
                            .hasMessageContaining("riptide.inventory.file");
                });
    }

    @Test
    void failsStartupNamingTheProblemOnABadFile() throws Exception {
        final Path file = this.tempDir.resolve("inventory.yaml");
        Files.writeString(file, """
                riptide:
                  snmp:
                    agents:
                      "10.20.0.0/16":
                        credentials: nope
                """);

        this.runner
                .withPropertyValues("riptide.inventory.file=" + file)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("10.20.0.0/16")
                            .hasMessageContaining("nope");
                });
    }

    private static ExporterIdentity netflow(final String address, final long domain) {
        try {
            return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), domain);
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException(address, e);
        }
    }
}
