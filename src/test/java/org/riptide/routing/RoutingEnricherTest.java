/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.routing;

import org.junit.jupiter.api.Test;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Source;
import org.riptide.routing.RoutingConfig.PrefixInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RoutingEnricherTest {

    private static RoutingConfig config(final Map<String, PrefixInfo> prefixes, final Map<Long, String> asNames) {
        final RoutingConfig config = new RoutingConfig();
        config.setPrefixes(prefixes);
        config.setAsNames(asNames);
        config.parsePrefixes();
        return config;
    }

    private static EnrichedFlow flow(final String src, final Long srcAs) throws UnknownHostException {
        return EnrichedFlow.builder()
                .srcAddr(InetAddress.getByName(src))
                .dstAddr(InetAddress.getByName("198.51.100.1"))
                .srcAs(srcAs)
                .build();
    }

    private static void enrich(final RoutingConfig config, final EnrichedFlow flow) throws Exception {
        new RoutingEnricher(config)
                .enrich(new Source("here", InetAddress.getByName("127.0.0.1")), flow)
                .get();
    }

    @Test
    public void exporterZeroIsFilledFromPrefix() throws Exception {
        final RoutingConfig config = config(
                Map.of("203.0.113.0/24", new PrefixInfo(64500L, "Example Carrier")), Map.of());
        final EnrichedFlow flow = flow("203.0.113.7", 0L);

        enrich(config, flow);

        assertThat(flow.getSrcAs()).isEqualTo(64500L);
        assertThat(flow.getSrcAsOrg()).isEqualTo("Example Carrier");
    }

    @Test
    public void exporterProvidedAsWinsAndGetsNamed() throws Exception {
        final RoutingConfig config = config(
                Map.of("203.0.113.0/24", new PrefixInfo(64500L, "Wrong")),
                Map.of(65001L, "Exporter Said So"));
        final EnrichedFlow flow = flow("203.0.113.7", 65001L);

        enrich(config, flow);

        assertThat(flow.getSrcAs()).isEqualTo(65001L);
        assertThat(flow.getSrcAsOrg()).isEqualTo("Exporter Said So");
    }

    @Test
    public void longestPrefixWinsInEitherInsertionOrder() throws Exception {
        for (final boolean coarseFirst : new boolean[]{true, false}) {
            final Map<String, PrefixInfo> prefixes = new LinkedHashMap<>();
            if (coarseFirst) {
                prefixes.put("10.0.0.0/8", new PrefixInfo(64500L, "coarse"));
                prefixes.put("10.20.0.0/16", new PrefixInfo(64501L, "fine"));
            } else {
                prefixes.put("10.20.0.0/16", new PrefixInfo(64501L, "fine"));
                prefixes.put("10.0.0.0/8", new PrefixInfo(64500L, "coarse"));
            }
            final EnrichedFlow flow = flow("10.20.30.5", 0L);

            enrich(config(prefixes, Map.of()), flow);

            assertThat(flow.getSrcAs()).isEqualTo(64501L);
        }
    }

    @Test
    public void ipv6PrefixesWork() throws Exception {
        final RoutingConfig config = config(
                Map.of("2001:db8::/32", new PrefixInfo(64501L, "Example IX")), Map.of());
        final EnrichedFlow flow = flow("2001:db8::42", null);

        enrich(config, flow);

        assertThat(flow.getSrcAs()).isEqualTo(64501L);
        assertThat(flow.getSrcAsOrg()).isEqualTo("Example IX");
    }

    @Test
    public void prefixOrgIsNotOverwrittenByAsNames() throws Exception {
        final RoutingConfig config = config(
                Map.of("203.0.113.0/24", new PrefixInfo(64500L, "From Prefix")),
                Map.of(64500L, "From As-Names"));
        final EnrichedFlow flow = flow("203.0.113.7", 0L);

        enrich(config, flow);

        assertThat(flow.getSrcAsOrg()).isEqualTo("From Prefix");
    }

    @Test
    public void noConfigurationIsANoOp() throws Exception {
        final RoutingConfig config = config(Map.of(), Map.of());
        final EnrichedFlow flow = flow("203.0.113.7", 0L);

        enrich(config, flow);

        assertThat(flow.getSrcAs()).isEqualTo(0L);
        assertThat(flow.getSrcAsOrg()).isNull();
    }

    @Test
    public void hostFormPrefixMatchesItsWholeBlock() throws Exception {
        // interface notation pasted from a router config: 10.0.0.5/24 means the /24 block
        final RoutingConfig config = config(
                Map.of("10.0.0.5/24", new PrefixInfo(64500L, "Pasted From Router")), Map.of());
        final EnrichedFlow flow = flow("10.0.0.7", 0L);

        enrich(config, flow);

        assertThat(flow.getSrcAs()).isEqualTo(64500L);
        assertThat(flow.getSrcAsOrg()).isEqualTo("Pasted From Router");
    }

    @Test
    public void duplicateBlocksFailStartupNamingBothKeys() {
        final Map<String, PrefixInfo> prefixes = new LinkedHashMap<>();
        prefixes.put("10.0.0.0/24", new PrefixInfo(64500L, "a"));
        prefixes.put("10.0.0.5/24", new PrefixInfo(64501L, "b"));

        assertThatThrownBy(() -> config(prefixes, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.0/24")
                .hasMessageContaining("10.0.0.5/24");
    }

    @Test
    public void invalidPrefixFailsStartup() {
        assertThatThrownBy(() -> config(Map.of("not-a-prefix!", new PrefixInfo(1L, "x")), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-a-prefix!");
    }
}
