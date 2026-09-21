/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * Storage shared by the option tables keyed on an {@link ExporterIdentity}: one inner cache per
 * exporter, holding rows pushed as NetFlow v9 / IPFIX option records. Interface names and
 * application names both live here, and any table shaped the same way can.
 *
 * <p>Nested per scope rather than flat on {@code (identity, key)}, so the key half can be bounded
 * on its own. A flat map with one size bound would evict across scopes instead, letting whoever
 * sprays hardest displace a real exporter's rows. That is the global-LRU hole
 * {@code SessionAdmission} exists to avoid.</p>
 *
 * <p><strong>Why the address index exists.</strong> Lookups happen on the ingest path, once or
 * twice per flow record, and they must fall back from the exact identity to the device address
 * because the option table and the flow records that reference it do not always share an
 * observation domain. A Catalyst 8000V sends its option tables under one observation domain and its
 * flow records under another; RFC 6759 and the interface table's own IANA scope both anchor the
 * table to the exporting process, not to a particular domain, so the device address is what the
 * fallback expresses. Scanning the outer cache for that address would be O(number of scopes) per
 * flow, and the scope count is attacker-reachable: the ceiling is
 * {@code maxSources × maxScopesPerSource}, 65,536 at the shipped defaults, so on a router where
 * the exact identity never matches an address spray would turn bounded memory into unbounded CPU.
 * {@code byAddress} makes the fallback proportional to the domains of the one device instead.</p>
 *
 * <p><strong>The no-scope-at-all rule.</strong> The fallback is consulted only when the flow's
 * identity has no scope of its own. A domain that sent its own table and simply does not know this
 * key gets an empty answer rather than a neighbour's row, so two exporting processes behind one
 * address share names only when one of them sends no table at all. That is exactly the c8000v
 * shape: domain 256 sends flows and no table, domain 6 sends the tables.</p>
 *
 * @param <K> the row key, {@code ifIndex} or packed {@code applicationId}
 * @param <V> the row value
 */
public final class ExporterScopedTable<K, V> {

    private final ConcurrentMap<InetAddress, Set<ExporterIdentity>> byAddress = new ConcurrentHashMap<>();

    /**
     * The outer bound is belt-and-braces. Reaching {@code accept} at all requires a template, and a
     * template requires admission, so the live scope population is already bounded upstream. What
     * that argument does not cover is the retention window: a scope displaced from its admission
     * budget stops receiving records but keeps its inner map until the TTL expires it, so a
     * sustained spray could hold more scopes here than are admitted at any instant. Bounding the
     * outer level too is what makes the documented worst-case product an actual ceiling rather than
     * a steady-state estimate.
     */
    private final Cache<ExporterIdentity, Cache<K, V>> table;

    private final Duration retention;
    private final int maxKeysPerScope;
    private final Runnable onSizeEviction;

    /**
     * @param retention how long a row and an idle scope survive, the exporters' re-send cadence
     * @param scopeCeiling the most scopes retained across every exporter, from
     *     {@link OptionTables#scopeCeiling}
     * @param maxKeysPerScope rows retained per scope, evicted least-recently-used within that scope
     * @param onSizeEviction run once per row evicted because its scope hit {@code maxKeysPerScope}.
     *     Only {@link RemovalCause#SIZE} runs it: expiry is the table working as designed, whereas a
     *     size eviction is the cap biting, and is the one that tells an attack from a cap set too
     *     low for a large chassis.
     */
    public ExporterScopedTable(final Duration retention, final long scopeCeiling,
            final int maxKeysPerScope, final Runnable onSizeEviction) {
        this.retention = retention;
        this.maxKeysPerScope = maxKeysPerScope;
        this.onSizeEviction = onSizeEviction;
        this.table = CacheBuilder.newBuilder()
                .expireAfterWrite(retention)
                .maximumSize(scopeCeiling)
                // every cause, so the index never outlives the scope it points at: an expired or
                // size-evicted identity left behind would make the fallback walk dead entries
                .<ExporterIdentity, Cache<K, V>>removalListener(notification -> unindex(notification.getKey()))
                .build();
    }

    /** This scope's rows, created and indexed on first use. */
    public Cache<K, V> scope(final ExporterIdentity identity) {
        final Cache<K, V> forScope;
        try {
            forScope = this.table.get(identity, () -> CacheBuilder.newBuilder()
                    .expireAfterWrite(this.retention)
                    .maximumSize(this.maxKeysPerScope)
                    .<K, V>removalListener(notification -> {
                        if (notification.getCause() == RemovalCause.SIZE) {
                            this.onSizeEviction.run();
                        }
                    })
                    .build());
        } catch (final ExecutionException e) {
            // The loader is a plain builder call and throws nothing checked; Guava still declares it.
            throw new IllegalStateException("option table scope for " + identity + " could not be created", e);
        }
        this.byAddress.computeIfAbsent(identity.deviceAddress(), address -> ConcurrentHashMap.newKeySet())
                .add(identity);
        return forScope;
    }

    /**
     * Exact identity first; then, only if the exact identity has no scope at all, the other
     * observation domains of the same device address. Never another device.
     */
    public Optional<V> lookup(final ExporterIdentity identity, final K key) {
        final Cache<K, V> exact = this.table.getIfPresent(identity);
        if (exact != null) {
            return Optional.ofNullable(exact.getIfPresent(key));
        }
        final Set<ExporterIdentity> sameDevice = this.byAddress.get(identity.deviceAddress());
        if (sameDevice == null) {
            return Optional.empty();
        }
        for (final ExporterIdentity sibling : sameDevice) {
            if (sibling.equals(identity)) {
                continue;
            }
            final Cache<K, V> scope = this.table.getIfPresent(sibling);
            if (scope != null) {
                final V value = scope.getIfPresent(key);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /** Approximate and cheap; exactly {@code true} when nothing was ever inserted. */
    public boolean isEmpty() {
        return this.table.size() == 0;
    }

    /** Scopes retained, approximate in the same way {@link Cache#size()} is. */
    public long size() {
        return this.table.size();
    }

    /** Identities the address index still points at; the index's own leak check. */
    int indexedIdentities() {
        this.table.cleanUp();
        int count = 0;
        for (final Set<ExporterIdentity> identities : this.byAddress.values()) {
            count += identities.size();
        }
        return count;
    }

    private void unindex(final ExporterIdentity identity) {
        if (identity == null) {
            return;
        }
        this.byAddress.computeIfPresent(identity.deviceAddress(), (address, identities) -> {
            identities.remove(identity);
            return identities.isEmpty() ? null : identities;
        });
    }
}
