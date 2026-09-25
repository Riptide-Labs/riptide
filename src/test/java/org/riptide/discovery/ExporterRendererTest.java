/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExporterRendererTest {

    private static final List<String> DEFAULT_LABELS =
            List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");

    private static RenderedExporters render(final List<TargetGroup> groups) {
        return ExporterRenderer.render(groups, DEFAULT_LABELS, "the endpoint");
    }

    private static TargetGroup group(final List<String> targets, final Map<String, String> labels) {
        return new TargetGroup(targets, labels);
    }

    @Test
    void theNameComesFromTheNetboxNameLabelAndTheAddressFromALabel() {
        final var rendered = render(List.of(group(
                List.of("firewall-01"),
                Map.of("__meta_netbox_name", "firewall-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1"))));

        assertThat(rendered.byName()).containsExactly(Map.entry("firewall-01", "10.0.0.1"));
        assertThat(rendered.skipped()).isZero();
    }

    @Test
    void theAddressLabelsAreConsultedInOrder() {
        final var rendered = render(List.of(group(
                List.of("router-01"),
                Map.of("__meta_netbox_name", "router-01",
                        "__meta_netbox_primary_ip6", "2001:db8::1"))));

        assertThat(rendered.byName()).containsEntry("router-01", "2001:db8::1");
    }

    @Test
    void anIpv4LabelWinsOverAnIpv6OneBecauseItIsListedFirst() {
        final var rendered = render(List.of(group(
                List.of("router-01"),
                Map.of("__meta_netbox_name", "router-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1",
                        "__meta_netbox_primary_ip6", "2001:db8::1"))));

        assertThat(rendered.byName()).containsEntry("router-01", "10.0.0.1");
    }

    @Test
    void withNoMatchingLabelTheTargetItselfIsTheAddress() {
        final var rendered = render(List.of(group(List.of("10.0.0.5:9100"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("10.0.0.5", "10.0.0.5"));
    }

    @Test
    void aBracketedIpv6TargetLosesItsBracketsAndItsPort() {
        final var rendered = render(List.of(group(List.of("[2001:db8::2]:9100"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("2001:db8::2", "2001:db8::2"));
    }

    @Test
    void aBareIpv6TargetIsUsedAsIsBecauseNoPartOfItIsAPort() {
        final var rendered = render(List.of(group(List.of("2001:db8::2"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("2001:db8::2", "2001:db8::2"));
    }

    @Test
    void aBracketedIpv6TargetWithNoPortLosesOnlyItsBrackets() {
        final var rendered = render(List.of(group(List.of("[2001:db8::2]"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("2001:db8::2", "2001:db8::2"));
    }

    @Test
    void aNetboxNameWithAPortOnTheTargetKeepsTheLabelName() {
        final var rendered = render(List.of(group(
                List.of("firewall-01:4242"),
                Map.of("__meta_netbox_name", "firewall-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1"))));

        assertThat(rendered.byName()).containsExactly(Map.entry("firewall-01", "10.0.0.1"));
    }

    @Test
    void anEntryWithNoUsableAddressIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of("has-no-ip"), Map.of("__meta_netbox_name", "has-no-ip")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.9"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aGroupWithSeveralTargetsBecomesOneEntryPerTarget() {
        final var rendered = render(List.of(group(
                List.of("10.0.0.1:9100", "10.0.0.2:9100"), Map.of())));

        assertThat(rendered.byName()).containsOnlyKeys("10.0.0.1", "10.0.0.2");
    }

    @Test
    void aGroupWithMixedUsabilityTargetsCountsOnlyTheUnusableOnes() {
        final var rendered = render(List.of(group(
                List.of("10.0.0.1:9100", "not-an-address:9100"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("10.0.0.1", "10.0.0.1"));
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aBlankTargetNameIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of(""), Map.of("__meta_netbox_primary_ip4", "10.0.0.9")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.10"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    /**
     * One device legitimately appearing in two service discovery roles. It collides on neither
     * key: the name claims one address, and the address claims one name.
     */
    @Test
    void identicalNameAndAddressPairsAppearingTwiceDoNotCollide() {
        final var rendered = render(List.of(
                group(List.of("a"), Map.of("__meta_netbox_name", "dup",
                        "__meta_netbox_primary_ip4", "10.0.0.5")),
                group(List.of("b"), Map.of("__meta_netbox_name", "dup",
                        "__meta_netbox_primary_ip4", "10.0.0.5"))));

        assertThat(rendered.byName()).containsExactly(Map.entry("dup", "10.0.0.5"));
    }

    @Test
    void collidingNamesAreRefusedAndEveryCollisionIsNamed() {
        assertThatThrownBy(() -> render(List.of(
                group(List.of("a"), Map.of("__meta_netbox_name", "sw1",
                        "__meta_netbox_primary_ip4", "10.0.0.1")),
                group(List.of("b"), Map.of("__meta_netbox_name", "sw1",
                        "__meta_netbox_primary_ip4", "10.0.0.2")),
                group(List.of("c"), Map.of("__meta_netbox_name", "sw2",
                        "__meta_netbox_primary_ip4", "10.0.0.3")),
                group(List.of("d"), Map.of("__meta_netbox_name", "sw2",
                        "__meta_netbox_primary_ip4", "10.0.0.4")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sw1")
                .hasMessageContaining("sw2")
                .hasMessageContaining("10.0.0.1")
                .hasMessageContaining("10.0.0.2")
                .hasMessageContaining("10.0.0.3")
                .hasMessageContaining("10.0.0.4");
    }

    /**
     * The merged document would otherwise carry two exporter entries sharing an address with no
     * observation domain, and {@code PinnedPrefixMatcher.Builder.add} raises its "resolve to the
     * same canonical prefix" refusal in the loader's second pass — outside the {@code Problems}
     * collector, so it fails startup and is a counted reload failure on every later poll. NetBox
     * enforces uniqueness on device name, not on primary IP, so an HA pair sharing one is ordinary.
     */
    @Test
    void collidingAddressesAreRefusedAndEveryCollisionIsNamed() {
        assertThatThrownBy(() -> render(List.of(
                group(List.of("a"), Map.of("__meta_netbox_name", "fw-ha-1",
                        "__meta_netbox_primary_ip4", "10.0.0.1")),
                group(List.of("b"), Map.of("__meta_netbox_name", "fw-ha-2",
                        "__meta_netbox_primary_ip4", "10.0.0.1")),
                group(List.of("c"), Map.of("__meta_netbox_name", "vc-member-1",
                        "__meta_netbox_primary_ip4", "10.0.0.2")),
                group(List.of("d"), Map.of("__meta_netbox_name", "vc-member-2",
                        "__meta_netbox_primary_ip4", "10.0.0.2")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("fw-ha-1")
                .hasMessageContaining("fw-ha-2")
                .hasMessageContaining("10.0.0.1")
                .hasMessageContaining("vc-member-1")
                .hasMessageContaining("vc-member-2")
                .hasMessageContaining("10.0.0.2");
    }

    @Test
    void aDeviceNamedFourDigitsWithNoAddressLabelIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of("1234"), Map.of("__meta_netbox_name", "1234")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.9"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aLabelThatIsNotAnAddressIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of("bad-device"), Map.of("__meta_netbox_name", "bad-device",
                        "__meta_netbox_primary_ip4", "not-an-ip")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.9"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aDeviceNamedLikeADottedNumberWithNoAddressLabelIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of("12.34"), Map.of("__meta_netbox_name", "12.34")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.9"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aDocumentYieldingNoEntriesIsRefusedRatherThanPublishedEmpty() {
        assertThatThrownBy(() -> render(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("no exporter");
    }

    @Test
    void aDocumentWhoseEveryEntryIsSkippedIsAlsoRefused() {
        assertThatThrownBy(() -> render(List.of(
                group(List.of("no-ip"), Map.of("__meta_netbox_name", "no-ip")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
    }

    /**
     * The ordering is the record's, and it is natural ordering whatever the caller hands in.
     *
     * <p>Two things are pinned here that the type alone did not give. The component was declared
     * {@code SortedMap}, which reads as a guarantee and was not one: {@code TreeMap}'s copy
     * constructor is overloaded, and the {@code SortedMap} form inherits the source's comparator,
     * so a reverse-ordered map in produced a reverse-ordered document out. And a caller with no
     * ordering at all, which is what {@link ExporterRenderer} now hands over, must still come back
     * ordered (#808).</p>
     */
    @Test
    void theRecordOrdersWhateverItIsHandedByName() {
        final Map<String, String> unordered = new LinkedHashMap<>();
        unordered.put("zulu", "10.0.0.26");
        unordered.put("alpha", "10.0.0.1");

        assertThat(new RenderedExporters(unordered, 0).byName().keySet())
                .as("an unordered map is what the renderer hands over")
                .containsExactly("alpha", "zulu");

        final SortedMap<String, String> reversed = new TreeMap<>(Comparator.reverseOrder());
        reversed.putAll(unordered);

        assertThat(new RenderedExporters(reversed, 0).byName().keySet())
                .as("a caller's comparator must not decide the document's order")
                .containsExactly("alpha", "zulu");
    }

    @Test
    void theSameGroupsInADifferentOrderRenderIdentically() {
        final TargetGroup one = group(List.of("a"), Map.of("__meta_netbox_name", "alpha",
                "__meta_netbox_primary_ip4", "10.0.0.1"));
        final TargetGroup two = group(List.of("b"), Map.of("__meta_netbox_name", "bravo",
                "__meta_netbox_primary_ip4", "10.0.0.2"));

        assertThat(render(List.of(one, two)).byName().toString())
                .as("the content hash short-circuit depends on this; an unstable order would swap "
                        + "the inventory on every poll")
                .isEqualTo(render(List.of(two, one)).byName().toString());
    }

    private static final String DEVICES = "https://netbox/api/dcim/devices/";
    private static final String VMS = "https://netbox/api/virtualization/virtual-machines/";

    private static TargetGroup exporter(final String name, final String address) {
        return group(List.of(name), Map.of("__meta_netbox_name", name, "__meta_netbox_primary_ip4", address));
    }

    private static RenderedExporters renderBoth(final List<TargetGroup> devices, final List<TargetGroup> vms) {
        return ExporterRenderer.render(List.of(
                new ExporterRenderer.EndpointGroups(DEVICES, devices),
                new ExporterRenderer.EndpointGroups(VMS, vms)), DEFAULT_LABELS);
    }

    @Test
    void aNameClaimedOnTwoEndpointsListsEachAddressWithItsEndpoint() {
        assertThatThrownBy(() -> renderBoth(
                List.of(exporter("hook", "192.168.10.5")),
                List.of(exporter("hook", "100.110.244.41"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(DEVICES + ", " + VMS + " returned 1 exporter name(s)")
                .hasMessageEndingWith("  hook -> 192.168.10.5 (" + DEVICES + "), 100.110.244.41 (" + VMS + ")");
    }

    @Test
    void anAddressClaimedOnTwoEndpointsListsEachNameWithItsEndpoint() {
        assertThatThrownBy(() -> renderBoth(
                List.of(exporter("sw1", "10.0.0.1")),
                List.of(exporter("vm1", "10.0.0.1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned 1 exporter address(es)")
                .hasMessageEndingWith("  10.0.0.1 -> sw1 (" + DEVICES + "), vm1 (" + VMS + ")");
    }

    @Test
    void theSameNameAndAddressOnTwoEndpointsIsOneEntry() {
        final var rendered = renderBoth(List.of(exporter("hook", "10.0.0.5")), List.of(exporter("hook", "10.0.0.5")));

        assertThat(rendered.byName()).containsExactly(Map.entry("hook", "10.0.0.5"));
    }

    /**
     * The case the per-endpoint check exists for: a token that lost view permission on virtual
     * machines gets a 200 with no results, and a merged check would publish the devices alone.
     */
    @Test
    void oneEmptyEndpointRefusesTheRenderEvenWhenTheOtherHasEntries() {
        assertThatThrownBy(() -> renderBoth(List.of(exporter("sw1", "10.0.0.1")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(VMS + " yielded no exporter entries (0 entries were skipped for want of a usable "
                        + "address). Keeping the running inventory: a source of truth that answers with nothing "
                        + "is more often a filter or permission mistake than an emptied fleet.");
    }

    @Test
    void everyEmptyEndpointIsNamedInOneRefusalWithItsOwnSkipCount() {
        assertThatThrownBy(() -> renderBoth(
                List.of(group(List.of("no-ip"), Map.of("__meta_netbox_name", "no-ip"))),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(DEVICES + " yielded no exporter entries (1 entry was skipped"
                        + " for want of a usable address)." + System.lineSeparator()
                        + VMS + " yielded no exporter entries (0 entries were skipped");
    }

    @Test
    void theSkipCountIsSummedAcrossEndpoints() {
        final TargetGroup noAddress = group(List.of("no-ip"), Map.of("__meta_netbox_name", "no-ip"));
        final var rendered = renderBoth(
                List.of(exporter("sw1", "10.0.0.1"), noAddress, noAddress),
                List.of(exporter("vm1", "10.0.0.2"), noAddress, noAddress, noAddress));

        assertThat(rendered.skipped()).isEqualTo(5);
        assertThat(rendered.byName()).containsOnlyKeys("sw1", "vm1");
    }

    /** One endpoint has nothing to disambiguate, so its report reads exactly as before. */
    @Test
    void aSingleEndpointCollisionReportCarriesNoEndpointAnnotation() {
        assertThatThrownBy(() -> render(List.of(exporter("sw1", "10.0.0.1"), exporter("sw1", "10.0.0.2"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("globally." + System.lineSeparator() + "  sw1 -> 10.0.0.1, 10.0.0.2");
    }
}
