/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryDocument;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.inventory.TestCredentials;
import org.riptide.pipeline.ExporterIdentity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The NetBox device source reads NetBox's own API, so a site that cannot install the service
 * discovery plugin can still use discovery. It emits the same intermediate the plugin does, so
 * everything downstream stays shared rather than reimplemented.
 */
class NetboxDeviceSourceTest {

    private static URL url(final String spec) {
        try {
            return new URI(spec).toURL();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One NetBox device page: the paging envelope the plugin does not have. */
    private static String page(final String next, final String... devices) {
        return """
                {"count": %d, "next": %s, "previous": null, "results": [%s]}
                """.formatted(devices.length, next == null ? "null" : "\"" + next + "\"",
                String.join(",", devices));
    }

    private static String device(final String name, final String ip4) {
        return """
                {"id": 1, "name": "%s", "status": {"value": "active", "label": "Active"},
                 "role": {"id": 3, "name": "Edge router", "slug": "edge-router"},
                 "device_type": {"model": "SRX345", "manufacturer": {"slug": "juniper"}},
                 "primary_ip4": %s}
                """.formatted(name, ip4 == null ? "null" : "{\"address\": \"" + ip4 + "\"}");
    }

    private static NetboxDeviceSource source(final Map<String, String> pages) {
        return new NetboxDeviceSource(url("http://netbox.test/api/dcim/devices/"),
                p -> {
                    final String body = pages.get(p.toString());
                    if (body == null) {
                        throw new IOException("no such page: " + p);
                    }
                    return body.getBytes(StandardCharsets.UTF_8);
                },
                () -> "the endpoint");
    }

    @Test
    void aDeviceBecomesATargetTheSharedRendererUnderstands() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24")))).targets();

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().targets()).containsExactly("edge-01");
        assertThat(groups.getFirst().labels())
                .containsEntry("__meta_netbox_name", "edge-01")
                .containsEntry("__meta_netbox_primary_ip4", "10.0.0.1");
    }

    @Test
    void aPrefixLengthIsStrippedFromTheAddress() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24")))).targets();

        assertThat(groups.getFirst().labels().get("__meta_netbox_primary_ip4"))
                .as("a prefix reaching the matcher would attribute one device's name to a whole subnet")
                .isEqualTo("10.0.0.1");
    }

    @Test
    void theStrippedAddressMatchesThatHostAndNoOther() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24")))).targets();
        final var rendered = ExporterRenderer.render(groups,
                List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6"), "the endpoint");
        final String yaml = "riptide:\n  exporters:\n    edge-01:\n      address: "
                + rendered.byName().get("edge-01") + "\n";
        final var snapshot = InventoryLoader.parse(
                new SnmpProfilesConfig(Map.of(), Map.of()), yaml, "probe");

        assertThat(matches(snapshot, "10.0.0.1")).as("the device itself").isTrue();
        assertThat(matches(snapshot, "10.0.0.2")).as("a neighbour in the same /24").isFalse();
        assertThat(matches(snapshot, "10.0.0.250")).as("and any other host in it").isFalse();
    }

    private static boolean matches(final org.riptide.inventory.InventorySnapshot snapshot,
                                   final String address) throws Exception {
        return snapshot.exporterView()
                .match(new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0L))
                .isPresent();
    }

    @Test
    void everyPageIsReadBeforeAnythingIsRendered() throws Exception {
        final var groups = source(Map.of(
                "http://netbox.test/api/dcim/devices/",
                page("http://netbox.test/api/dcim/devices/?offset=1", device("edge-01", "10.0.0.1/24")),
                "http://netbox.test/api/dcim/devices/?offset=1",
                page(null, device("edge-02", "10.0.0.2/24")))).targets();

        assertThat(groups).hasSize(2);
        assertThat(groups.stream().map(g -> g.targets().getFirst()).toList())
                .containsExactly("edge-01", "edge-02");
    }

    @Test
    void aPageThatCannotBeReadFailsTheWholeWalk() {
        final var source = source(Map.of("http://netbox.test/api/dcim/devices/",
                page("http://netbox.test/api/dcim/devices/?offset=1", device("edge-01", "10.0.0.1/24"))));

        assertThatThrownBy(source::targets)
                .as("a short read is indistinguishable downstream from a fleet that shrank")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aDeviceWithNoPrimaryAddressCarriesNoAddressLabel() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("no-ip-01", null)))).targets();

        assertThat(groups.getFirst().labels())
                .containsEntry("__meta_netbox_name", "no-ip-01")
                .doesNotContainKey("__meta_netbox_primary_ip4");
    }

    @Test
    void aDeviceWithNoAddressIsSkippedAndCountedByTheSharedRenderer() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("edge-01", "10.0.0.1/24"), device("no-ip-01", null)))).targets();
        final var rendered = ExporterRenderer.render(groups,
                List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6"), "the endpoint");

        assertThat(rendered.byName()).containsOnlyKeys("edge-01");
        assertThat(rendered.skipped()).as("skipping is the shared renderer's, not reimplemented here").isEqualTo(1);
    }

    @Test
    void theServiceDiscoveryShapeIsRefusedWithAMessageNamingTheOtherType() {
        final var source = new NetboxDeviceSource(url("http://netbox.test/api/dcim/devices/"),
                p -> "[{\"targets\":[\"a\"]}]".getBytes(StandardCharsets.UTF_8), () -> "the endpoint");

        assertThatThrownBy(source::targets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("results")
                .hasMessageContaining("prometheus-sd");
    }

    /**
     * The fields this source actually reads, which is only the name and the two nested primary
     * addresses. The previous version of this test asserted the name alone and called itself a pin
     * on `role` and `status`, which this class never reads; it would have passed unchanged if NetBox
     * renamed either.
     */
    @Test
    void onlyTheNameAndTheNestedPrimaryAddressesAreRead() throws Exception {
        final String deviceWithRenamedRoleAndStatus = """
                {"id": 1, "name": "edge-01",
                 "device_role": {"slug": "edge-router"}, "status": "active",
                 "primary_ip4": {"address": "10.0.0.1/24"},
                 "primary_ip6": {"address": "2001:db8::1/64"}}
                """;
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, deviceWithRenamedRoleAndStatus))).targets();

        assertThat(groups.getFirst().labels())
                .as("role and status are the operator's filter's business, not this source's")
                .containsEntry("__meta_netbox_name", "edge-01")
                .containsEntry("__meta_netbox_primary_ip4", "10.0.0.1")
                .containsEntry("__meta_netbox_primary_ip6", "2001:db8::1");
    }

    @Test
    void anAddressNestedUnderSomethingElseIsNotRead() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/", page(null, """
                {"id": 1, "name": "edge-01", "primary_ip4": {"display": "10.0.0.1/24"}}
                """))).targets();

        assertThat(groups.getFirst().labels())
                .as("the address lives under 'address'; reading a sibling would invent one")
                .doesNotContainKey("__meta_netbox_primary_ip4");
    }

    @Test
    void aPercentEscapeInTheFilterSurvivesUnchanged() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/"), "name__ic=core%20switch");

        assertThat(first.toString())
                .as("a filter pasted from NetBox's own address bar must reach NetBox as written")
                .contains("name__ic=core%20switch")
                .doesNotContain("%2520");
    }

    @Test
    void aPercentEscapeAlreadyInTheEndpointSurvivesToo() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/?name=a%26b"), "status=active");

        assertThat(first.toString())
                .as("re-escaping would turn one parameter into two, or two into one")
                .contains("name=a%26b");
    }

    @Test
    void aTermMerelyEndingInOrderingDoesNotSuppressTheStableOrdering() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/"), "cf_ordering=x");

        assertThat(first.toString())
                .as("a substring test would drop the ordering and let pagination duplicate a device")
                .contains("cf_ordering=x")
                .contains("&ordering=id");
    }

    @Test
    void aNamelessFleetStillHitsTheBoundRatherThanWalkingForever() {
        final List<String> nameless = new ArrayList<>();
        for (int i = 0; i <= NetboxDeviceSource.MAX_DEVICES; i++) {
            nameless.add("{\"id\": " + i + ", \"primary_ip4\": {\"address\": \"10.0.0.1/24\"}}");
        }
        final var source = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, nameless.toArray(new String[0]))));

        assertThatThrownBy(source::targets)
                .as("counting emitted groups left a nameless fleet unbounded")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.filter");
    }

    @Test
    void aNextLinkOnAnotherOriginIsRefusedRatherThanSentTheToken() {
        final var source = source(Map.of("http://netbox.test/api/dcim/devices/",
                page("http://evil.test/api/dcim/devices/?offset=1", device("edge-01", "10.0.0.1/24"))));

        assertThatThrownBy(source::targets)
                .as("the Authorization header is bound to the client, so it would follow the link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different origin")
                .hasMessageContaining("evil.test");
    }

    @Test
    void theFilterAndAStableOrderingReachTheRequest() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/"), "status=active&role=leaf");

        assertThat(first.toString())
                .contains("status=active")
                .contains("role=leaf")
                .contains("ordering=id");
    }

    @Test
    void aPastedSeparatorAndASpaceDoNotBreakTheUrl() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/"), "?status=active&name=core switch");

        assertThat(first.toString())
                .as("a leading ? is natural to paste and a space is what breaks naive joining")
                .doesNotContain("devices/??")
                .contains("name=core%20switch");
    }

    @Test
    void anEndpointThatAlreadyHasAQueryIsJoinedWithAnAmpersand() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/?brief=false"), "status=active");

        assertThat(first.toString()).contains("brief=false&status=active");
    }

    @Test
    void anOrderingInTheFilterIsNotOverridden() {
        final URL first = NetboxDeviceSource.firstPage(
                url("http://netbox.test/api/dcim/devices/"), "ordering=name");

        assertThat(first.toString()).contains("ordering=name");
        assertThat(first.toString().split("ordering=", -1)).as("exactly one ordering term").hasSize(2);
    }

    @Test
    void noFilterStillRequestsAStableOrdering() {
        assertThat(NetboxDeviceSource.firstPage(url("http://netbox.test/api/dcim/devices/"), null).toString())
                .as("without it an insert mid-walk shifts every remaining page")
                .endsWith("ordering=id");
    }

    @Test
    void aFilterMatchingNothingIsRefusedRatherThanEmptyingTheTree() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/", page(null))).targets();

        assertThat(groups).isEmpty();
        assertThatThrownBy(() -> ExporterRenderer.render(groups,
                List.of("__meta_netbox_primary_ip4"), "the endpoint"))
                .as("the shared empty-result refusal, not a second copy of it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
    }

    @Test
    void twoDevicesResolvingToOneNameAreRefusedByTheSharedRenderer() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("sw1", "10.0.0.1/24"), device("sw1", "10.0.0.2/24")))).targets();

        assertThatThrownBy(() -> ExporterRenderer.render(groups,
                List.of("__meta_netbox_primary_ip4"), "the endpoint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sw1");
    }

    @Test
    void twoDevicesResolvingToOneAddressAreRefusedByTheSharedRenderer() throws Exception {
        final var groups = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, device("sw1", "10.0.0.1/24"), device("sw2", "10.0.0.1/24")))).targets();

        assertThatThrownBy(() -> ExporterRenderer.render(groups,
                List.of("__meta_netbox_primary_ip4"), "the endpoint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.1");
    }

    @Test
    void aRenderedTreeComposesWithTheFilesAgentRangesLikeAnyOther() throws Exception {
        final var document = new ComposedInventoryDocument(
                new FixedFile("""
                        riptide:
                          snmp:
                            agents:
                              "10.0.0.0/8":
                                credentials: corp-v3
                        """),
                source(Map.of("http://netbox.test/api/dcim/devices/",
                        page(null, device("edge-01", "10.0.0.1/24")))),
                () -> "the endpoint", configured(), new MetricRegistry());
        final var inventory = new Inventory(
                new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of()), document);
        inventory.load();

        assertThat(inventory.snapshot().agentCount()).as("the file still owns the ranges").isEqualTo(1);
        assertThat(inventory.snapshot().exporterCount()).as("discovery owns the exporters").isEqualTo(1);
    }

    @Test
    void aWalkPastTheBoundIsRefusedRatherThanTruncated() {
        final List<String> many = new ArrayList<>();
        for (int i = 0; i <= NetboxDeviceSource.MAX_DEVICES; i++) {
            many.add(device("edge-" + i, "10.0.0.1/24"));
        }
        final var source = source(Map.of("http://netbox.test/api/dcim/devices/",
                page(null, many.toArray(new String[0]))));

        assertThatThrownBy(source::targets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.filter");
    }

    private static DiscoveryConfig configured() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("http://netbox.test/api/dcim/devices/");
        config.setType("netbox-api");
        return config;
    }

    private record FixedFile(String text) implements InventoryDocument {
        @Override
        public String name() {
            return "inventory.yaml";
        }
    }
}
