/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceDiscoveryParserTest {

    private static java.util.List<TargetGroup> parse(final String json) {
        return ServiceDiscoveryParser.parse(json.getBytes(StandardCharsets.UTF_8), "the endpoint");
    }

    @Test
    void readsTargetsAndLabels() {
        final var groups = parse("""
                [{"targets":["firewall-01"],"labels":{"__meta_netbox_primary_ip4":"10.0.0.1"}}]
                """);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().targets()).containsExactly("firewall-01");
        assertThat(groups.getFirst().labels()).containsEntry("__meta_netbox_primary_ip4", "10.0.0.1");
    }

    @Test
    void anEmptyArrayParsesToNoGroups() {
        assertThat(parse("[]")).isEmpty();
    }

    @Test
    void labelsMayBeOmittedEntirely() {
        final var groups = parse("""
                [{"targets":["10.0.0.1:9100"]}]
                """);

        assertThat(groups.getFirst().labels()).isEmpty();
    }

    @Test
    void aTopLevelObjectIsRefusedNamingTheSource() {
        assertThatThrownBy(() -> parse("""
                {"results":[]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("JSON array");
    }

    @Test
    void aMissingTargetsFieldIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"labels":{"a":"b"}}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targets");
    }

    @Test
    void aNonStringLabelValueIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"targets":["a"],"labels":{"port":9100}}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
    }

    @Test
    void malformedJsonIsRefusedNamingTheSource() {
        assertThatThrownBy(() -> parse("[{"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void aNonStringTargetIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"targets":[9100],"labels":{}}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-string target");
    }

    @Test
    void aLabelsFieldThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"targets":["a"],"labels":["b"]}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void anArrayElementThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> parse("""
                ["just-a-string"]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an object");
    }
}
