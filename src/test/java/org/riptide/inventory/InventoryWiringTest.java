/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.node.LegacyNodesFlagDayCheck;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.secrets.SecretRefConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryWiringTest {

    @TempDir
    Path tempDir;

    // The flag-day check reads the environment's property sources, so importing it into
    // WiringConfiguration would otherwise let the real JVM environment decide these tests.
    // Anyone with RIPTIDE_NODES_* exported — which is exactly a developer testing the 0.8
    // migration this check exists for — would see unrelated failures across the class. Pinned
    // empty here; the cases that need the env form replace it themselves.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
            .withUserConfiguration(WiringConfiguration.class);

    @Configuration
    @EnableConfigurationProperties({SnmpProfilesConfig.class, InventoryConfig.class})
    @Import({Inventory.class, InventoryMisplacementCheck.class, PollKeyMigrationCheck.class,
            LegacyNodesFlagDayCheck.class, SecretRefConverter.class})
    static class WiringConfiguration {
    }

    /**
     * The flag day fires from a real context, not just from its own static entry point.
     *
     * <p>Every case in {@code LegacyNodesFlagDayCheckTest} calls the static overload directly, so
     * the whole suite stayed green with the {@code @PostConstruct} body gutted: 1460 tests, zero
     * failures, and a 0.8 container booting clean with its device configuration doing nothing. The
     * two sibling checks were already imported here and asserted through {@code hasFailed()}; this
     * one was not, which is the only reason the gap existed.</p>
     */
    @Test
    void aLegacyNodesTreeFailsStartupThroughTheRealContext() {
        this.runner
                .withPropertyValues("riptide.nodes.core-router.subnet-address=10.0.0.1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("riptide.nodes")
                            .hasMessageContaining("riptide convert");
                });
    }

    /**
     * The environment form fails startup too — the case the check exists for.
     *
     * <p>A container configured entirely through the environment is the deployment most likely to
     * still carry a legacy tree, so this is driven through a real
     * {@link SystemEnvironmentPropertySource} rather than through inlined properties: that source
     * is what supplies the {@code RIPTIDE_NODES_*} spelling the normaliser has to fold.</p>
     */
    @Test
    void theEnvironmentFormFailsStartupThroughTheRealContext() {
        this.runner
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS", "10.0.0.1"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("riptide convert");
                });
    }

    /**
     * A Service named {@code riptide-nodes-*} must not take the context down.
     *
     * <p>Kubernetes injects {@code {SVCNAME}_PORT} for every Service, so this is the shape that
     * crash-looped every pod in a namespace. Asserted here rather than only against the static
     * entry point, because a context that fails to start is the actual consequence.</p>
     */
    @Test
    void aServiceLinkDoesNotTakeTheContextDown() {
        this.runner
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("RIPTIDE_NODES_HEADLESS_PORT", "tcp://10.0.0.1:6343",
                                        "RIPTIDE_NODES_HEADLESS_SERVICE_HOST", "10.0.0.1"))))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** The 0.9 surfaces are not caught by the prefix — a sloppy match on "nodes" would be. */
    @Test
    void theCurrentInventorySurfacesAreNotMistakenForTheLegacyTree() {
        this.runner
                .withPropertyValues(
                        "riptide.snmp.credentials.corp-v3.version=v3",
                        "riptide.snmp.credentials.corp-v3.security-name=riptide",
                        "riptide.snmp.credentials.corp-v3.auth-protocol=sha1",
                        "riptide.snmp.credentials.corp-v3.auth-passphrase=0123456789abcdef")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aProfileDeclaredWithNoValuesBindsExactlyTheBuiltInDefault() {
        // the two default paths must not drift: @DefaultValue feeds the binder, the
        // constants feed builtInDefault(), and only value equality catches a mismatch.
        // 'sparse' also proves per-component defaulting, filling the three keys it omits
        this.runner
                .withPropertyValues("riptide.snmp.polling.bare.timeout=500",
                        "riptide.snmp.polling.sparse.retries=4")
                .run(context -> {
                    final var polling = context.getBean(SnmpProfilesConfig.class).polling();
                    assertThat(polling.get("bare")).isEqualTo(PollingProfile.builtInDefault());
                    assertThat(polling.get("sparse").retries()).isEqualTo(4);
                    assertThat(polling.get("sparse").refreshInterval())
                            .isEqualTo(PollingProfile.builtInDefault().refreshInterval());
                    assertThat(polling.get("sparse").snapshotExpiry())
                            .isEqualTo(PollingProfile.builtInDefault().snapshotExpiry());
                    assertThat(polling.get("sparse").timeout())
                            .isEqualTo(PollingProfile.builtInDefault().timeout());
                });
    }

    @Test
    void boundValueObjectsAreImmutableSoBindTimeValidationIsAnInvariant() {
        // the point of the refactor: a validated set cannot be retuned afterwards.
        // If either type regains a setter, this stops compiling
        assertThat(CredentialSet.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
        assertThat(PollingProfile.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
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
                    assertThat(agent.get().credentials().securityName()).isEqualTo("riptide");
                    // the stated reason the profiles stay Spring-bound: SecretRef
                    // values bind through the existing converter
                    assertThat(agent.get().credentials().authPassphrase()).isNotNull();
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
    void pollingProfileShapesAreValidatedAtBind() {
        this.runner
                .withPropertyValues("riptide.snmp.polling.hasty.timeout=-5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("hasty").hasMessageContaining("timeout");
                });

        // expiry shorter than refresh is a warning, never an error
        this.runner
                .withPropertyValues(
                        "riptide.snmp.polling.tight.refresh-interval=10m",
                        "riptide.snmp.polling.tight.snapshot-expiry=1m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void retiredGlobalPollKeysFailStartupPointingAtProfiles() {
        this.runner
                .withPropertyValues("riptide.snmp.poll.refresh-interval-ms=300000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("refresh-interval-ms")
                            .hasMessageContaining("riptide.snmp.polling");
                });

        // fleet-level keys under the same prefix keep binding
        this.runner
                .withPropertyValues("riptide.snmp.poll.pool-width=8", "riptide.snmp.poll.max-exporters=1024")
                .run(context -> assertThat(context).hasNotFailed());
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
