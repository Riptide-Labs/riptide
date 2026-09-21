/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class OptionTablesTest {

    private static ExporterIdentity identity(final String host, final long domain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    private static Cache<Integer, String> inner(final int key, final String value) {
        final Cache<Integer, String> cache = CacheBuilder.newBuilder().build();
        cache.put(key, value);
        return cache;
    }

    @Test
    void lookupPrefersTheExactIdentity() throws Exception {
        final Cache<ExporterIdentity, Cache<Integer, String>> table = CacheBuilder.newBuilder().build();
        final var exact = identity("10.10.3.1", 256);
        final var other = identity("10.10.3.1", 6);
        table.put(exact, inner(2, "from-domain-256"));
        table.put(other, inner(2, "from-domain-6"));

        assertThat(OptionTables.lookup(table, exact, 2)).contains("from-domain-256");
    }

    @Test
    void lookupFallsBackToAnotherDomainOfTheSameDevice() throws Exception {
        final Cache<ExporterIdentity, Cache<Integer, String>> table = CacheBuilder.newBuilder().build();
        final var optionsDomain = identity("10.10.3.1", 6);
        final var flowsDomain = identity("10.10.3.1", 256);
        table.put(optionsDomain, inner(2, "Gi2"));

        assertThat(OptionTables.lookup(table, flowsDomain, 2)).contains("Gi2");
    }

    @Test
    void lookupNeverCrossesToAnotherDevice() throws Exception {
        final Cache<ExporterIdentity, Cache<Integer, String>> table = CacheBuilder.newBuilder().build();
        final var deviceA = identity("10.10.3.1", 6);
        final var deviceB = identity("10.10.3.2", 256);
        table.put(deviceA, inner(2, "Gi2"));

        assertThat(OptionTables.lookup(table, deviceB, 2)).isEmpty();
    }
}
