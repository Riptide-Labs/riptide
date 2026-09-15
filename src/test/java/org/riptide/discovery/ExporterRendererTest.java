/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
