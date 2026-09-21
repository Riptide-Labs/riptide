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

    /**
     * The outer cache must expire a scope on idleness, not on a fixed timer from creation: a
     * busy exporter that keeps re-sending its table has to stay resolvable for as long as it does.
     *
     * <p>A row rewritten on every iteration can't tell the two policies apart: {@code scope()}'s
     * loading {@code get} recreates an expired entry and writes straight into the fresh copy, so the
     * just-written key is always found no matter which policy governs the outer cache. The
     * discriminating row is one written once, part-way through the scope's life, and checked while
     * it is still within its own {@code expireAfterWrite} budget: a creation-anchored outer timer
     * (the bug) wipes it out from under that still-valid budget the moment some other row's write
     * lands after the fixed window, while an access-anchored one (the fix) does not, because the
     * scope keeps getting touched.</p>
     */
    @Test
    void aScopeThatKeepsBeingRefreshedOutlivesTheRetention() throws Exception {
        final var table = new ExporterScopedTable<Integer, String>(Duration.ofMillis(300), 1_000L, 64, () -> {
        });
        final var identity = identity("10.10.3.1", 6);

        put(table, identity, 0, "bootstrap"); // t=0: creates the scope
        Thread.sleep(200);
        put(table, identity, 1, "the-row"); // t=200: its own 300 ms budget runs to ~t=500
        for (int i = 0; i < 2; i++) { // t=300, t=400: other rows keep the scope busy
            Thread.sleep(100);
            put(table, identity, 200 + i, "keepalive-" + i);
        }

        assertThat(table.lookup(identity, 1))
                .as("t≈400: past the 300 ms a creation-anchored timer would allow, but well inside "
                        + "\"the-row\"'s own budget (due ~t=500) and the scope has been touched every "
                        + "100 ms since it was created")
                .contains("the-row");
    }

    /** The other half of the same rule: a scope nobody touches expires after the retention. */
    @Test
    void anIdleScopeExpiresOnTheRetention() throws Exception {
        final var table = new ExporterScopedTable<Integer, String>(Duration.ofMillis(50), 1_000L, 64, () -> {
        });
        final var identity = identity("10.10.3.1", 6);
        put(table, identity, 1, "Gi2");

        Thread.sleep(150);

        assertThat(table.lookup(identity, 1)).isEmpty();
        assertThat(table.indexedIdentities()).isZero();
    }
}
