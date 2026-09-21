/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExporterScopedTableTest {

    private static ExporterIdentity identity(final String host, final long domain) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    private static ExporterScopedTable<Integer, String> table() {
        return table(1_000L);
    }

    private static ExporterScopedTable<Integer, String> table(final long scopeCeiling) {
        return new ExporterScopedTable<>(Duration.ofMinutes(1), scopeCeiling, 64, () -> {
        });
    }

    private static void put(final ExporterScopedTable<Integer, String> table,
            final ExporterIdentity identity, final int key, final String value) {
        table.scope(identity).put(key, value);
    }

    @Test
    void lookupPrefersTheExactIdentity() throws Exception {
        final var table = table();
        final var exact = identity("10.10.3.1", 256);
        put(table, exact, 2, "from-domain-256");
        put(table, identity("10.10.3.1", 6), 2, "from-domain-6");

        assertThat(table.lookup(exact, 2)).contains("from-domain-256");
    }

    /** The c8000v: the flow's domain sends no table at all, so the device's other domain answers. */
    @Test
    void lookupFallsBackToAnotherDomainOfTheSameDevice() throws Exception {
        final var table = table();
        put(table, identity("10.10.3.1", 6), 2, "Gi2");

        assertThat(table.lookup(identity("10.10.3.1", 256), 2)).contains("Gi2");
    }

    /**
     * Two exporting processes behind one address are separate exporters, and one must not answer
     * for the other. A domain that sent its own table and does not know this key gets nothing.
     */
    @Test
    void aDomainWithItsOwnScopeNeverBorrowsAnotherDomainsRows() throws Exception {
        final var table = table();
        final var ownDomain = identity("10.10.3.1", 256);
        put(table, ownDomain, 1, "its-own-row");
        put(table, identity("10.10.3.1", 6), 2, "another-processes-row");

        assertThat(table.lookup(ownDomain, 2)).isEmpty();
    }

    @Test
    void lookupNeverCrossesToAnotherDevice() throws Exception {
        final var table = table();
        put(table, identity("10.10.3.1", 6), 2, "Gi2");

        assertThat(table.lookup(identity("10.10.3.2", 256), 2)).isEmpty();
    }

    /**
     * The index is a second reference to every identity in the outer cache, so it has to be dropped
     * when the cache drops one. Otherwise the fallback keeps walking identities whose scopes are
     * gone, and the index itself grows past the scope ceiling that bounds the cache.
     */
    @Test
    void theAddressIndexForgetsAnEvictedIdentity() throws Exception {
        final var table = table(1L);
        put(table, identity("10.10.3.1", 6), 2, "Gi2");
        // one scope is all this table may hold, so inserting a second evicts the first
        put(table, identity("10.10.3.1", 7), 3, "Gi3");

        assertThat(table.lookup(identity("10.10.3.1", 256), 2))
                .as("the evicted domain's rows are gone, not served from a stale index entry")
                .isEmpty();
        assertThat(table.size()).isEqualTo(1);
        assertThat(table.indexedIdentities())
                .as("the index holds exactly the identities the cache still has")
                .isEqualTo(1);
    }

    @Test
    void anEmptyTableIsEmpty() throws Exception {
        final var table = table();

        assertThat(table.isEmpty()).isTrue();
        put(table, identity("10.10.3.1", 6), 2, "Gi2");
        assertThat(table.isEmpty()).isFalse();
    }
}
