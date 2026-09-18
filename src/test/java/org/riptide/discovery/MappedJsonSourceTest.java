/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.ExporterIdentity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Any JSON endpoint, read by paths an operator wrote.
 *
 * <p>The mapping is the whole feature, so these cover what a path does at each edge and what the
 * source does with a value it cannot use. What happens after the mapping is the shared renderer's
 * and is not re-tested here beyond proving it is reached (#800).</p>
 */
class MappedJsonSourceTest {

    private static final String ENDPOINT = "http://assets.internal/api/devices";

    private static URL url(final String spec) {
        try {
            return new URI(spec).toURL();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MappedJsonSource.MappingPaths paths(final String items, final String name,
                                                       final String address, final String next) {
        return new MappedJsonSource.MappingPaths(
                JsonPath.of(items, "riptide.discovery.mapping.items"),
                JsonPath.of(name, "riptide.discovery.mapping.name"),
                JsonPath.of(address, "riptide.discovery.mapping.address"),
                next == null ? null : JsonPath.of(next, "riptide.discovery.mapping.next"));
    }

    private static MappedJsonSource source(final Map<String, String> pages,
                                           final MappedJsonSource.MappingPaths paths) {
        return new MappedJsonSource(url(ENDPOINT), page -> {
            final String body = pages.get(page.toString());
            if (body == null) {
                throw new IOException("no such page: " + page);
            }
            return body.getBytes(StandardCharsets.UTF_8);
        }, () -> "the endpoint", paths);
    }

    private static MappedJsonSource source(final String body) {
        return source(Map.of(ENDPOINT, body), paths("results", "hostname", "mgmt_ip", null));
    }

    @Test
    void aShapeNeitherOtherSourceCanReadBecomesTargets() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}]}
                """).targets();

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().targets())
                .as("the address is the target, which is what a generic producer emits")
                .containsExactly("10.0.0.1");
        assertThat(groups.getFirst().labels())
                .containsEntry("__meta_netbox_name", "edge-01");
    }

    @Test
    void aNestedEnvelopeIsFoundWhereTheOperatorSaidItIs() throws Exception {
        final var groups = source(Map.of(ENDPOINT, """
                {"data": {"devices": [{"n": "edge-01", "ip": "10.0.0.1"}]}, "meta": {"count": 1}}
                """), paths("data.devices", "n", "ip", null)).targets();

        assertThat(groups.getFirst().targets()).containsExactly("10.0.0.1");
    }

    @Test
    void aNestedFieldInsideOneDeviceIsReached() throws Exception {
        final var groups = source(Map.of(ENDPOINT, """
                {"results": [{"hostname": "edge-01", "primary": {"ip": {"addr": "10.0.0.1"}}}]}
                """), paths("results", "hostname", "primary.ip.addr", null)).targets();

        assertThat(groups.getFirst().targets()).containsExactly("10.0.0.1");
    }

    @Test
    void theMappedDeviceRendersAndMatchesAFlowFromThatAddressOnly() throws Exception {
        final var rendered = ExporterRenderer.render(source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}]}
                """).targets(), List.of("__meta_netbox_primary_ip4"), "the endpoint");
        final var snapshot = InventoryLoader.parse(new SnmpProfilesConfig(Map.of(), Map.of()),
                "riptide:\n  exporters:\n    edge-01:\n      address: "
                        + rendered.byName().get("edge-01") + "\n", "probe");

        assertThat(matches(snapshot, "10.0.0.1")).as("the device itself").isTrue();
        assertThat(matches(snapshot, "10.0.0.2")).as("and no neighbour").isFalse();
    }

    private static boolean matches(final InventorySnapshot snapshot, final String address) throws Exception {
        return snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0L))
                .isPresent();
    }

    @Test
    void aDeviceMissingEitherPathIsSkippedRatherThanRefused() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"},
                             {"hostname": "no-address"},
                             {"mgmt_ip": "10.0.0.9"}]}
                """).targets();

        assertThat(groups)
                .as("an endpoint legitimately carries entries this mapping does not describe")
                .hasSize(1);
    }

    @Test
    void twoDevicesMappingToOneNameAreRefusedByTheSharedRenderer() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"},
                             {"hostname": "edge-01", "mgmt_ip": "10.0.0.2"}]}
                """).targets();

        assertThatThrownBy(() -> ExporterRenderer.render(groups, List.of("x"), "the endpoint"))
                .as("the downstream refusals reach this source without being reimplemented")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("edge-01");
    }

    /**
     * The one shape the path language cannot fix, named rather than left to be guessed at.
     *
     * <p>Every device is skipped, so without this the shared renderer reports yielding no entries,
     * which names a filter and sends the operator to a key that is not their problem.</p>
     */
    @Test
    void anAddressCarryingAPrefixLengthSaysSoRatherThanYieldingNothing() {
        final var source = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1/24"},
                             {"hostname": "edge-02", "mgmt_ip": "10.0.0.2/24"}]}
                """);

        assertThatThrownBy(source::targets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefix length")
                .hasMessageContaining("10.0.0.1/24")
                .hasMessageContaining("riptide.discovery.mapping.address")
                .hasMessageContaining("netbox-api");
    }

    /**
     * The diagnostic fires on evidence, not on an empty result.
     *
     * <p>Every device here is skipped by the source because the name path matches nothing, so the
     * result is empty with no prefixed address anywhere. Claiming a prefix problem would send an
     * operator to fix an address format that is not what went wrong.</p>
     *
     * <p>The first version of this test asserted on the renderer's message for an address the
     * source emits, which never reached this branch at all: the guard survived a mutation that
     * removed it.</p>
     */
    @Test
    void anEmptyResultWithNoPrefixedAddressMakesNoSuchClaim() throws Exception {
        final var source = source("""
                {"results": [{"other": "edge-01", "mgmt_ip": "10.0.0.1"},
                             {"other": "edge-02", "mgmt_ip": "10.0.0.2"}]}
                """);

        assertThat(source.targets())
                .as("nothing mapped, and nothing to blame it on: the renderer reports the empty result")
                .isEmpty();
    }

    @Test
    void anAddressUnusableForAnotherReasonIsLeftToTheRenderer() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "not-an-address"}]}
                """).targets();

        assertThat(groups)
                .as("a value with no prefix length is this source's to emit and the renderer's to judge")
                .hasSize(1);
        assertThatThrownBy(() -> ExporterRenderer.render(groups, List.of("x"), "the endpoint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yielded no exporter entries")
                .hasMessageNotContaining("prefix length");
    }

    /**
     * A CIDR block is a legal exporter entry, so it must not be treated as the prefix problem.
     *
     * <p>The renderer accepts one deliberately: the matcher is a prefix trie, so a range is an
     * entry. Rejecting every value with a slash would drop a block that works under the other
     * sources, and then advise switching to NetBox about an address NetBox has nothing to do
     * with.</p>
     */
    @Test
    void aCidrBlockIsAnEntryRatherThanThePrefixProblem() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "site-a", "mgmt_ip": "10.0.0.0/24"}]}
                """).targets();

        assertThat(groups.getFirst().targets())
                .as("a range is a legal entry, and the other sources accept it")
                .containsExactly("10.0.0.0/24");
    }

    @Test
    void aWalkOverEmptyPagesThatNeverEndIsRefused() {
        final var source = source(Map.of(ENDPOINT, """
                {"results": [], "paging": {"next": "%s"}}
                """.formatted(ENDPOINT)), paths("results", "hostname", "mgmt_ip", "paging.next"));

        assertThatThrownBy(source::targets)
                .as("the device bound never moves here, so the page bound is the only thing that ends it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pages");
    }

    @Test
    void aBareTopLevelArrayIsReadWithNoItemsPath() throws Exception {
        final var groups = source(Map.of(ENDPOINT, """
                [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}]
                """), new MappedJsonSource.MappingPaths(null,
                JsonPath.of("hostname", "riptide.discovery.mapping.name"),
                JsonPath.of("mgmt_ip", "riptide.discovery.mapping.address"), null)).targets();

        assertThat(groups.getFirst().targets())
                .as("the commonest shape of all, and one no path can name")
                .containsExactly("10.0.0.1");
    }

    @Test
    void aRelativeNextLinkIsResolvedAgainstThePageItCameFrom() throws Exception {
        final var groups = source(Map.of(
                ENDPOINT, """
                        {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}],
                         "paging": {"next": "/api/devices?page=2"}}
                        """,
                "http://assets.internal/api/devices?page=2", """
                        {"results": [{"hostname": "edge-02", "mgmt_ip": "10.0.0.2"}], "paging": {}}
                        """),
                paths("results", "hostname", "mgmt_ip", "paging.next")).targets();

        assertThat(groups.stream().map(g -> g.targets().getFirst()).toList())
                .as("an arbitrary endpoint often answers with a relative link; NetBox never does")
                .containsExactly("10.0.0.1", "10.0.0.2");
    }

    /**
     * A prefixed address is still emitted, so the shared renderer skips and counts it on
     * {@code discovery.skipped}. Dropping it at the source made a fleet lose devices with the gauge
     * reading zero and nothing logged.
     */
    @Test
    void aPrefixedAddressBesideUsableOnesIsCountedRatherThanVanishing() throws Exception {
        final var groups = source("""
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"},
                             {"hostname": "edge-02", "mgmt_ip": "10.0.0.2/24"}]}
                """).targets();
        final var rendered = ExporterRenderer.render(groups, List.of("x"), "the endpoint");

        assertThat(rendered.byName()).as("the usable one publishes").containsOnlyKeys("edge-01");
        assertThat(rendered.skipped())
                .as("and the unusable one is visible on the gauge rather than silently gone")
                .isEqualTo(1);
    }

    @Test
    void anItemsPathThatIsNotAnArrayIsRefusedNamingTheKeyAndWhatWasFound() {
        final var source = source(Map.of(ENDPOINT, """
                {"results": {"edge-01": "10.0.0.1"}}
                """), paths("results", "hostname", "mgmt_ip", null));

        assertThatThrownBy(source::targets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.mapping.items")
                .hasMessageContaining("an object");
    }

    @Test
    void withNoNextPathConfiguredOnlyOneRequestIsMade() throws Exception {
        final var groups = source(Map.of(ENDPOINT, """
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}],
                 "next": "http://assets.internal/api/devices?page=2"}
                """), paths("results", "hostname", "mgmt_ip", null)).targets();

        assertThat(groups)
                .as("the link is there; nothing was configured to follow it, so it is not followed")
                .hasSize(1);
    }

    @Test
    void withANextPathTheWalkContinues() throws Exception {
        final var groups = source(Map.of(
                ENDPOINT, """
                        {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}],
                         "paging": {"next": "http://assets.internal/api/devices?page=2"}}
                        """,
                ENDPOINT + "?page=2", """
                        {"results": [{"hostname": "edge-02", "mgmt_ip": "10.0.0.2"}], "paging": {}}
                        """),
                paths("results", "hostname", "mgmt_ip", "paging.next")).targets();

        assertThat(groups.stream().map(g -> g.targets().getFirst()).toList())
                .containsExactly("10.0.0.1", "10.0.0.2");
    }

    /** The shared walk's rule, reaching this source too, which is what the NetBox spec promises. */
    @Test
    void aNextLinkOnAnotherOriginIsRefusedForThisSourceAsWell() {
        final var source = source(Map.of(ENDPOINT, """
                {"results": [{"hostname": "edge-01", "mgmt_ip": "10.0.0.1"}],
                 "paging": {"next": "http://evil.test/api/devices?page=2"}}
                """), paths("results", "hostname", "mgmt_ip", "paging.next"));

        assertThatThrownBy(source::targets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different origin")
                .hasMessageContaining("evil.test");
    }
}
