/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryConfigTest {

    @Test
    void theDefaultsAreTheOnesTheDesignNames() {
        final DiscoveryConfig config = new DiscoveryConfig();

        assertThat(config.getAuthScheme()).isEqualTo("Token");
        assertThat(config.getInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getAddressLabels())
                .containsExactly("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");
    }

    @Test
    void anUnparseableUrlFailsNamingTheKeyAndTheValue() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("not a url");

        assertThatThrownBy(config::endpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.url")
                .hasMessageContaining("not a url")
                .hasCauseInstanceOf(MalformedURLException.class);
    }

    /**
     * The failure message is derived on every fetch, not once at startup, so a credential in it
     * is logged on every poll forever. The trailing space is what makes this URL unparseable, so
     * both halves of the message — the value and the parser's own complaint, which quotes the
     * whole offending URL — are built from a string carrying the token.
     */
    @Test
    void anUnparseableUrlCarryingCredentialsRedactsThemFromBothHalvesOfTheMessage() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://svc:s3cr3t@netbox.example.com/api/devices/ ");

        assertThatThrownBy(config::endpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.url")
                .hasMessageContaining("https://***@netbox.example.com/api/devices/")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("svc:")
                .cause()
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void theRedactedSpellingIsTheOneTheClientLogs() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://svc:s3cr3t@netbox.example.com/api/devices/");

        assertThat(config.describe()).isEqualTo("https://***@netbox.example.com/api/devices/");
    }

    @Test
    void anUnsetUrlIsNamedByItsKey() {
        assertThat(new DiscoveryConfig().describe()).isEqualTo("riptide.discovery.url (unset)");
    }

    @Test
    void aParseableUrlBecomesAnEndpoint() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/plugins/prometheus-sd/devices/");

        assertThat(config.endpoint().getHost()).isEqualTo("netbox.example.com");
    }
}
